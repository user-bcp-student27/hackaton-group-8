package pe.wallet;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;

public class ApiError extends WebApplicationException {
    public ApiError(int status, String code, String message) {
        super(message, Response.status(status).entity(Map.of("code", code, "message", message)).build());
    }
}
