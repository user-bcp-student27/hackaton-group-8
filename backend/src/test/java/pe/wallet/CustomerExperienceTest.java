package pe.wallet;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(CustomerExperienceTest.CustomerProfile.class)
class CustomerExperienceTest {
    public static class CustomerProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("wallet.lab.enabled", "false", "wallet.db.url", "jdbc:h2:mem:customer;DB_CLOSE_DELAY=-1");
        }
    }

    @Inject Wallet wallet;

    @Test void customerModeBlocksSimulationAndKeepsDomainActionsAvailable() {
        given().get("/api/experience").then().statusCode(200).body("laboratory", equalTo(false));
        String token = wallet.login("demo-ana");
        given().header("X-Wallet-Request", "1").header("Idempotency-Key", Wallet.id())
                .cookie("wallet_session", token).contentType("application/json")
                .body(Map.of("destination", "luis.demo", "amount", 1, "channel", "ALIAS", "scenario", "DECLINED"))
                .post("/api/payments").then().statusCode(403).body("code", equalTo("LAB_DISABLED"));
        String reference = given().header("X-Wallet-Request", "1").header("Idempotency-Key", Wallet.id())
                .cookie("wallet_session", token).contentType("application/json")
                .body(Map.of("destination", "luis.demo", "amount", 1, "channel", "ALIAS"))
                .post("/api/payments").then().statusCode(200).body("status", equalTo("SUCCEEDED")).extract().path("id");
        String ops = wallet.login("demo-ops");
        given().header("X-Wallet-Request", "1").cookie("wallet_session", ops).contentType("application/json")
                .body(Map.of("eventId", Wallet.id(), "outcome", "DECLINED"))
                .post("/api/ops/payments/" + reference + "/callback").then().statusCode(403).body("code", equalTo("LAB_DISABLED"));
        given().header("X-Wallet-Request", "1").cookie("wallet_session", ops).contentType("application/json")
                .body(Map.of("outcome", "DECLINED", "amount", 2, "missing", false))
                .post("/api/ops/payments/" + reference + "/fixture").then().statusCode(403).body("code", equalTo("LAB_DISABLED"));
        given().header("X-Wallet-Request", "1").contentType("application/json").body(Map.of("userId", "cafe.demo"))
                .post("/api/demo/login").then().statusCode(200);
    }
}