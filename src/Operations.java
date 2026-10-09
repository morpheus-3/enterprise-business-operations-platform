import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Java 21 REST + JDBC service. Monetary amounts are integer cents. */
public class Operations {
    static final Gson JSON = new Gson();
    static String url = System.getenv().getOrDefault("OPERATIONS_DB", "jdbc:h2:./target/operations");
    static Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static void initialize(Map<String,String> tokens) throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS api_users(token_hash VARCHAR(64) PRIMARY KEY, role VARCHAR(20) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS inventory(sku VARCHAR(80) PRIMARY KEY, name VARCHAR(200) NOT NULL, quantity INT CHECK(quantity>=0), price_cents BIGINT CHECK(price_cents>0))");
            s.execute("CREATE TABLE IF NOT EXISTS transactions(id VARCHAR(80) PRIMARY KEY, sku VARCHAR(80) REFERENCES inventory(sku), quantity INT CHECK(quantity>0), total_cents BIGINT, actor VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor VARCHAR(64), action VARCHAR(40), sku VARCHAR(80), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("DELETE FROM api_users");
            for (var entry : tokens.entrySet()) {
                try (var p = c.prepareStatement("INSERT INTO api_users VALUES(?,?)")) {
                    p.setString(1, hash(entry.getValue())); p.setString(2, entry.getKey()); p.executeUpdate();
                }
            }
        }
    }
    static String role(String token) throws Exception {
        try (var c = connect(); var p = c.prepareStatement("SELECT role FROM api_users WHERE token_hash=?")) {
            p.setString(1, hash(token));
            try (var rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    static List<Map<String,Object>> rows(String sql) throws SQLException {
        try (var c = connect(); var p = c.prepareStatement(sql); var rs = p.executeQuery()) {
            var results = new ArrayList<Map<String,Object>>();
            while (rs.next()) {
                var row = new LinkedHashMap<String,Object>();
                for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                results.add(row);
            }
            return results;
        }
    }
    static String string(JsonObject body, String key, int max) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing string: " + key);
        String value = body.get(key).getAsString().trim();
        if (value.isEmpty() || value.length()>max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    static long integer(JsonObject body, String key, long min, long max) {
        try {
            long value = body.get(key).getAsBigDecimal().longValueExact();
            if (value<min || value>max) throw new IllegalArgumentException("Invalid " + key);
            return value;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid integer: " + key); }
    }
    static void audit(Connection c, String actor, String action, String sku) throws SQLException {
        try (var p = c.prepareStatement("INSERT INTO audit(actor,action,sku) VALUES(?,?,?)")) {
            p.setString(1, actor); p.setString(2, action); p.setString(3, sku); p.executeUpdate();
        }
    }
    static Map<String,Object> create(JsonObject body, String actor) throws Exception {
        String sku=string(body,"sku",80), name=string(body,"name",200);
        long qty=integer(body,"quantity",0,1_000_000), price=integer(body,"price_cents",1,100_000_000);
        try (var c=connect()) {
            c.setAutoCommit(false);
            try (var p=c.prepareStatement("INSERT INTO inventory VALUES(?,?,?,?)")) {
                p.setString(1,sku); p.setString(2,name); p.setLong(3,qty); p.setLong(4,price); p.executeUpdate();
                audit(c,actor,"CREATE",sku); c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
        return Map.of("sku",sku,"created",true);
    }
    static Map<String,Object> sale(JsonObject body, String actor) throws Exception {
        String id=string(body,"idempotency_key",80), sku=string(body,"sku",80);
        int quantity=(int)integer(body,"quantity",1,1_000_000);
        try (var c=connect()) {
            c.setAutoCommit(false);
            try {
                // Lock stock before checking replay: concurrent same-SKU requests serialize.
                long price; int stock;
                try (var p=c.prepareStatement("SELECT quantity,price_cents FROM inventory WHERE sku=? FOR UPDATE")) {
                    p.setString(1,sku);
                    try(var rs=p.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("Unknown SKU"); stock=rs.getInt(1); price=rs.getLong(2); }
                }
                try (var p=c.prepareStatement("SELECT sku,quantity,total_cents,actor FROM transactions WHERE id=?")) {
                    p.setString(1,id);
                    try(var rs=p.executeQuery()) {
                        if(rs.next()) {
                            if(!sku.equals(rs.getString(1)) || quantity!=rs.getInt(2) || !actor.equals(rs.getString(4))) throw new IllegalArgumentException("Idempotency key was used for another request");
                            long total=rs.getLong(3); c.rollback(); return Map.of("id",id,"total_cents",total,"replayed",true);
                        }
                    }
                }
                if(stock<quantity) throw new IllegalArgumentException("Insufficient stock");
                long total=Math.multiplyExact(price,quantity);
                try(var p=c.prepareStatement("UPDATE inventory SET quantity=quantity-? WHERE sku=?")) { p.setInt(1,quantity); p.setString(2,sku); p.executeUpdate(); }
                try(var p=c.prepareStatement("INSERT INTO transactions(id,sku,quantity,total_cents,actor) VALUES(?,?,?,?,?)")) {
                    p.setString(1,id); p.setString(2,sku); p.setInt(3,quantity); p.setLong(4,total); p.setString(5,actor); p.executeUpdate();
                }
                audit(c,actor,"SALE",sku); c.commit();
                return Map.of("id",id,"total_cents",total,"replayed",false);
            } catch(Exception e) { c.rollback(); throw e; }
        }
    }
    static void restock(JsonObject body, String actor) throws Exception {
        String sku=string(body,"sku",80); int quantity=(int)integer(body,"quantity",1,1_000_000);
        try(var c=connect()) {
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("UPDATE inventory SET quantity=quantity+? WHERE sku=? AND quantity+?<=1000000")) {
                p.setInt(1,quantity); p.setString(2,sku); p.setInt(3,quantity);
                if(p.executeUpdate()!=1) throw new IllegalArgumentException("Unknown SKU or stock limit exceeded");
                audit(c,actor,"RESTOCK",sku); c.commit();
            } catch(Exception e) { c.rollback(); throw e; }
        }
    }
    static void respond(HttpExchange ex,int status,Object body) throws Exception {
        byte[] data=JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json");
        ex.getResponseHeaders().set("Cache-Control","no-store");
        ex.sendResponseHeaders(status,data.length);
        try(var output=ex.getResponseBody()) { output.write(data); }
    }
    static void handle(HttpExchange ex) {
        try {
            String path=ex.getRequestURI().getPath(), method=ex.getRequestMethod();
            if(path.equals("/") && method.equals("GET")) {
                byte[] html=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("index.html"));
                ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                ex.sendResponseHeaders(200,html.length);
                try(var output=ex.getResponseBody()) { output.write(html); }
                return;
            }
            if(path.equals("/status") && method.equals("GET")) { respond(ex,200,Map.of("service","operations","ready",true)); return; }
            String auth=ex.getRequestHeaders().getFirst("Authorization");
            String token=auth!=null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
            String role=role(token);
            if(role==null) { respond(ex,401,Map.of("detail","Valid bearer token required")); return; }
            String actor=hash(token);
            if(method.equals("GET")) {
                if(path.equals("/inventory")) respond(ex,200,rows("SELECT sku,name,quantity,price_cents FROM inventory ORDER BY sku"));
                else if(path.equals("/transactions")) respond(ex,200,rows("SELECT id,sku,quantity,total_cents,created_at FROM transactions ORDER BY created_at DESC"));
                else if(path.equals("/audit")) {
                    if(role.equals("admin")) respond(ex,200,rows("SELECT id,action,sku,created_at FROM audit ORDER BY id DESC"));
                    else respond(ex,403,Map.of("detail","Admin role required"));
                }
                else respond(ex,404,Map.of("detail","Unknown route"));
                return;
            }
            if(!method.equals("POST")) { respond(ex,405,Map.of("detail","Method not allowed")); return; }
            if(role.equals("viewer") || (path.equals("/inventory") && !role.equals("admin"))) { respond(ex,403,Map.of("detail","Role cannot perform this action")); return; }
            byte[] raw=ex.getRequestBody().readNBytes(16385);
            if(raw.length>16384) { respond(ex,413,Map.of("detail","Body too large")); return; }
            JsonObject body=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();
            if(path.equals("/inventory")) respond(ex,201,create(body,actor));
            else if(path.equals("/transactions")) respond(ex,201,sale(body,actor));
            else if(path.equals("/restock")) { restock(body,actor); respond(ex,200,Map.of("restocked",true)); }
            else respond(ex,404,Map.of("detail","Unknown route"));
        } catch(JsonParseException | IllegalArgumentException | IllegalStateException e) {
            try { respond(ex,422,Map.of("detail",String.valueOf(e.getMessage()))); } catch(Exception ignored) {}
        } catch(SQLException e) {
            try { respond(ex,409,Map.of("detail","Database constraint or concurrent transaction conflict")); } catch(Exception ignored) {}
        } catch(Exception e) {
            try { respond(ex,500,Map.of("detail","Internal service error")); } catch(Exception ignored) {}
        } finally { ex.close(); }
    }
    public static void main(String[] args) throws Exception {
        var tokens=new HashMap<String,String>();
        for(String role:List.of("admin","operator","viewer")) {
            String value=System.getenv("OPERATIONS_"+role.toUpperCase(Locale.ROOT)+"_TOKEN");
            if(value==null || value.length()<24) throw new IllegalArgumentException("Set a unique 24+ character OPERATIONS_"+role.toUpperCase(Locale.ROOT)+"_TOKEN");
            tokens.put(role,value);
        }
        if(new HashSet<>(tokens.values()).size()!=3) throw new IllegalArgumentException("Role tokens must differ");
        initialize(tokens);
        int port=Integer.parseInt(System.getenv().getOrDefault("OPERATIONS_PORT","8081"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",Operations::handle);
        server.setExecutor(Executors.newFixedThreadPool(8)); server.start();
        System.out.println("Operations listening on http://127.0.0.1:"+port);
    }
}
