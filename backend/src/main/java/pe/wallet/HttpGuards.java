package pe.wallet;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Provider
public class HttpGuards implements ContainerRequestFilter, ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())
                && !"1".equals(request.getHeaderString("X-Wallet-Request"))) {
            request.abortWith(Response.status(403).entity(Map.of("code", "REQUEST_GUARD",
                    "message", "Falta el encabezado de protección de la wallet.")).build());
        }
        if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
            byte[] body = request.getEntityStream().readNBytes(2_000_001);
            if (body.length > 2_000_000) {
                request.abortWith(Response.status(413).entity(Map.of("code", "BODY_TOO_LARGE", "message", "El cuerpo supera el límite permitido.")).build());
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("{") || !text.endsWith("}")) {
            request.abortWith(Response.status(400).entity(Map.of("code", "BODY_REQUIRED", "message", "Se requiere un cuerpo JSON.")).build());
                return;
            }
            request.setEntityStream(new ByteArrayInputStream(body));
        }
    }
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Cache-Control", "no-store");
        response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        response.getHeaders().putSingle("X-Frame-Options", "DENY");
        response.getHeaders().putSingle("Referrer-Policy", "no-referrer");
        response.getHeaders().putSingle("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
    }
}
