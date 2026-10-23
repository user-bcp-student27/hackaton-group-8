package pe.wallet;

import com.google.zxing.*;
import com.google.zxing.client.j2se.*;
import com.google.zxing.common.HybridBinarizer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WalletResource {
    @Inject Wallet wallet;
    @ConfigProperty(name = "wallet.lab.enabled", defaultValue = "false") boolean labEnabled;
    @CookieParam("wallet_session") String token;
    public record Login(String userId) {}
    public record QrInput(Long amount) {}
    public record QrImage(String image) {}
    public record KycDecision(String decision) {}

    private void laboratory() {
        if (!labEnabled) throw new ApiError(403, "LAB_DISABLED", "Las herramientas de prueba no están habilitadas.");
    }

    private Response session(String token) {
        NewCookie cookie = new NewCookie.Builder("wallet_session").value(token).path("/api")
                .httpOnly(true).sameSite(NewCookie.SameSite.STRICT).maxAge(28800).build();
        return Response.ok(Map.of("demo", true)).cookie(cookie).build();
    }
    @GET @Path("/health")
    public Map<String, Object> health() { return Map.of("status", "UP", "mode", "SYNTHETIC_DEMO", "version", "1.0.0"); }
    @GET @Path("/experience")
    public Object experience() { return Map.of("laboratory", labEnabled); }
    @POST @Path("/demo/login")
    public Response login(Login input) { return session(wallet.login(input.userId())); }
    @POST @Path("/logout")
    public Response logout() {
        wallet.logout(token);
        return Response.ok(Map.of("ok", true)).cookie(new NewCookie.Builder("wallet_session").value("").path("/api").maxAge(0).httpOnly(true).build()).build();
    }
    @POST @Path("/onboarding/start")
    public Response start(Wallet.OnboardingStart input) { return session(wallet.start(input)); }
    @POST @Path("/onboarding/complete")
    public Object complete(Wallet.OnboardingComplete input) {
        if (input.kyc() != null) laboratory();
        return wallet.complete(token, new Wallet.OnboardingComplete(input.name(), input.alias(), input.phone(), input.consent(),
                input.kyc() == null ? "APPROVED" : input.kyc()));
    }
    @POST @Path("/onboarding/step")
    public Object step(Wallet.OnboardingStep input) { return wallet.onboardingStep(token, input); }
    @GET @Path("/me")
    public Object me() { return wallet.me(token); }
    @GET @Path("/contacts")
    public Object contacts() { return wallet.contacts(token); }
    @POST @Path("/checkout/start")
    public Object checkout(Wallet.CheckoutInput input) { wallet.startCheckout(token, input.attemptId()); return Map.of("ok", true); }
    @POST @Path("/payments")
    public Object pay(@HeaderParam("Idempotency-Key") String key, Wallet.PaymentInput input) {
        if (input.scenario() != null) laboratory();
        return wallet.pay(token, key, new Wallet.PaymentInput(input.destination(), input.amount(), input.channel(),
                input.scenario() == null ? "APPROVED" : input.scenario(), input.qrId()));
    }
    @GET @Path("/payments")
    public Object history(@QueryParam("date") String day, @QueryParam("reference") String reference, @QueryParam("channel") String channel) {
        return wallet.history(token, day, reference, channel);
    }
    @GET @Path("/payments/{id}")
    public Object payment(@PathParam("id") String id) { return wallet.getPayment(token, id); }
    @POST @Path("/payments/{id}/cases")
    public Object support(@PathParam("id") String id, Wallet.CaseInput input) { return wallet.openCase(token, id, input); }
    @POST @Path("/qr")
    public Object createQr(QrInput input) {
        Wallet.require(input.amount() != null, "AMOUNT_REQUIRED", "Ingresa un monto para el QR.");
        Map<String, Object> qr = wallet.createQr(token, input.amount());
        try {
            var matrix = new MultiFormatWriter().encode("wallet-demo:" + qr.get("id"), BarcodeFormat.QR_CODE, 320, 320);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", bytes);
            qr.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
            return qr;
        } catch (WriterException | IOException error) { throw new IllegalStateException("QR generation failed", error); }
    }
    @POST @Path("/qr/decode")
    public Object decode(QrImage input) {
        wallet.me(token);
        Wallet.require(input.image() != null && input.image().startsWith("data:image/png;base64,") && input.image().length() <= 1400000,
                "INVALID_IMAGE", "Carga un QR PNG de la demo de hasta 1 MB.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(input.image().substring("data:image/png;base64,".length())); }
        catch (IllegalArgumentException error) { throw new ApiError(400, "INVALID_IMAGE", "El archivo PNG no es válido."); }
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Wallet.require(stream != null, "INVALID_IMAGE", "No se pudo abrir el archivo.");
            var readers = ImageIO.getImageReaders(stream);
            Wallet.require(readers.hasNext(), "INVALID_IMAGE", "El contenido no es una imagen PNG válida.");
            var reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(stream);
                Wallet.require(reader.getWidth(0) <= 2048 && reader.getHeight(0) <= 2048, "IMAGE_TOO_LARGE", "La imagen debe ser de hasta 2048 × 2048.");
                image = reader.read(0);
            } finally { reader.dispose(); }
            String payload = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
            Wallet.require(payload.matches("wallet-demo:[a-f0-9-]{36}"), "INVALID_QR", "Este QR no pertenece a la wallet demo.");
            return wallet.readQr(token, payload.substring("wallet-demo:".length()));
        } catch (com.google.zxing.NotFoundException | IOException error) { throw new ApiError(400, "QR_UNREADABLE", "No se pudo leer un QR válido. Usa el PNG generado por el comercio."); }
    }
    @GET @Path("/qr/{id}")
    public Object readQr(@PathParam("id") String id) { return wallet.readQr(token, id); }
    @GET @Path("/ops/cases")
    public Object cases() { return wallet.cases(token); }
    @POST @Path("/ops/cases/{id}")
    public Object decide(@PathParam("id") String id, Wallet.CaseDecision input) { return wallet.decideCase(token, id, input); }
    @POST @Path("/ops/payments/{id}/callback")
    public Object callback(@PathParam("id") String id, Wallet.CallbackInput input) { laboratory(); return wallet.callback(token, id, input); }
    @POST @Path("/ops/payments/{id}/fixture")
    public Object fixture(@PathParam("id") String id, Wallet.FixtureInput input) { laboratory(); wallet.fixture(token, id, input); return Map.of("ok", true); }
    @GET @Path("/ops/onboarding")
    public Object queue() { return wallet.onboardingQueue(token); }
    @POST @Path("/ops/onboarding/{id}")
    public Object review(@PathParam("id") String id, KycDecision input) { return wallet.reviewKyc(token, id, input.decision()); }
    @POST @Path("/ops/reconciliation")
    public Object reconciliation(Wallet.ReconcileInput input) { return wallet.reconcile(token, input.date(), input.channel()); }
    @POST @Path("/ops/mismatches/{id}/investigate")
    public Object investigate(@PathParam("id") String id) { wallet.investigate(token, id); return Map.of("ok", true); }
    @GET @Path("/ops/audit")
    public Object audit() { return wallet.auditLog(token); }
    @GET @Path("/ops/metrics")
    public Object metrics(@QueryParam("launchMonth") String month) { return wallet.metrics(token, month); }
    @POST @Path("/ops/acquisition")
    public Object acquisition(Wallet.AcquisitionInput input) { wallet.acquisition(token, input); return Map.of("ok", true); }
    @POST @Path("/assistant")
    public Object assistant(Wallet.AgentInput input) { return wallet.assistant(token, input); }
}
