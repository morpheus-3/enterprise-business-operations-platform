import com.google.gson.JsonParser;
import java.util.*;
import java.util.concurrent.*;

public class OperationsTest {
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static com.google.gson.JsonObject body(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        Operations.url="jdbc:h2:mem:test;DB_CLOSE_DELAY=-1";
        Operations.initialize(Map.of("admin","admin-test","operator","operator-test","viewer","viewer-test"));
        check("viewer".equals(Operations.role("viewer-test")),"RBAC role");
        check(Operations.role("invalid")==null,"Reject invalid token");
        Operations.create(body("{\"sku\":\"A\",\"name\":\"Widget\",\"quantity\":10,\"price_cents\":250}"),"admin");
        var request=body("{\"idempotency_key\":\"one\",\"sku\":\"A\",\"quantity\":3}");
        check(Operations.sale(request,"operator").get("total_cents").equals(750L),"Exact total");
        check(Operations.sale(request,"operator").get("replayed").equals(true),"Replay safe");
        check(((Number)Operations.rows("SELECT quantity FROM inventory").getFirst().get("quantity")).intValue()==7,"No double decrement");
        try { Operations.sale(body("{\"idempotency_key\":\"bad\",\"sku\":\"A\",\"quantity\":99}"),"operator"); throw new AssertionError("Oversell accepted"); } catch(IllegalArgumentException expected) {}
        check(Operations.rows("SELECT * FROM transactions").size()==1,"Rollback");
        Operations.restock(body("{\"sku\":\"A\",\"quantity\":2}"),"operator");
        var pool=Executors.newFixedThreadPool(4);
        var futures=new ArrayList<Future<Boolean>>();
        for(int i=0;i<4;i++) { final int id=i; futures.add(pool.submit(()-> { try { Operations.sale(body("{\"idempotency_key\":\"c"+id+"\",\"sku\":\"A\",\"quantity\":3}"),"operator"); return true; } catch(IllegalArgumentException e) { return false; } })); }
        int success=0; for(var future:futures) if(future.get()) success++;
        pool.shutdown(); check(success==3,"Concurrent stock protection");
        check(((Number)Operations.rows("SELECT quantity FROM inventory").getFirst().get("quantity")).intValue()==0,"Stock nonnegative");
        check(Operations.rows("SELECT * FROM audit").size()==6,"Transactional audit");
        System.out.println("PASS: RBAC lookup, totals, idempotency, rollback, restock, concurrent stock protection, audit");
    }
}
