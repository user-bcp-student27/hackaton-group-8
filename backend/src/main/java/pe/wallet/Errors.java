package pe.wallet;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import java.util.Map;

@Provider
public class Errors implements ExceptionMapper<Exception> {
    private static final Logger LOG = Logger.getLogger(Errors.class);

    @Override
    public Response toResponse(Exception error) {
        if (error instanceof ApiError api) return api.getResponse();
        if (error instanceof WebApplicationException web) {
            int status = web.getResponse().getStatus();
            return Response.status(status).entity(Map.of("code", "HTTP_" + status,
                    "message", status == 400 ? "Solicitud inválida. Revisa los campos." : "Solicitud no permitida.")).build();
        }
        LOG.error("Unexpected wallet failure", error);
        return Response.serverError().entity(Map.of("code", "INTERNAL_ERROR",
                "message", "No pudimos completar la operación. Consulta el estado antes de reintentar.")).build();
    }
}
