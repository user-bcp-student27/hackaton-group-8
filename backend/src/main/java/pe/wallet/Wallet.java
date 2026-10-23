package pe.wallet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static pe.wallet.Store.*;

@ApplicationScoped
public class Wallet {
    @Inject Store store;
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static final Set<String> OUTCOMES = Set.of("APPROVED", "DECLINED", "PENDING", "TIMEOUT");
    static final Set<String> TERMINALS = Set.of("SUCCEEDED", "FAILED", "EXPIRED");

    public record OnboardingStart(String campaign) {}
    public record OnboardingComplete(String name, String alias, String phone, boolean consent, String kyc) {}
    public record OnboardingStep(String step, String name, String alias, String phone, boolean consent) {}
    public record ReconcileInput(String date, String channel) {}
    public record CheckoutInput(String attemptId) {}
    public record PaymentInput(String destination, Long amount, String channel, String scenario, String qrId) {}
    public record CallbackInput(String eventId, String outcome) {}
    public record CaseInput(String category, String note) {}
    public record CaseDecision(String decision, String note) {}
    public record FixtureInput(String outcome, Long amount, boolean missing) {}
    public record AcquisitionInput(String month, String campaign, Long cents) {}
    public record AgentInput(String message, String paymentId) {}

    static String id() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }
    static String text(Object value) { return value == null ? "" : value.toString(); }
    static long number(Object value) { return ((Number) value).longValue(); }
    static void require(boolean condition, String code, String message) {
        if (!condition) throw new ApiError(400, code, message);
    }
    static String field(String input, String pattern, String label) {
        require(input != null && input.matches(pattern), "VALIDATION", label);
        return input;
    }
    static String clean(String input, int min, int max, String label) {
        require(input != null && input.trim().length() >= min && input.trim().length() <= max
                && input.chars().noneMatch(Character::isISOControl), "VALIDATION", label);
        return input.trim();
    }
    static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    Map<String, Object> actor(Connection c, String token) throws SQLException {
        if (token == null || token.length() > 200) throw new ApiError(401, "SESSION_REQUIRED", "Ingresa a tu wallet para continuar.");
        Map<String, Object> user = one(c, "SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>?",
                hash(token), now());
        if (user == null) throw new ApiError(401, "SESSION_EXPIRED", "Tu sesión expiró. Ingresa nuevamente.");
        return user;
    }
    void role(Map<String, Object> user, String expected) {
        if (!expected.equals(user.get("role"))) throw new ApiError(403, "FORBIDDEN", "Este perfil no tiene permiso para esta acción.");
    }
    void active(Map<String, Object> user) {
        if (!"ACTIVE".equals(user.get("status"))) throw new ApiError(403, "WALLET_INACTIVE", "Completa la validación de tu wallet antes de pagar.");
    }
    void audit(Connection c, String actor, String action, String reference, String correlation, String detail) throws SQLException {
        exec(c, "INSERT INTO audit VALUES(?,?,?,?,?,?,?,?)", id(), actor, action, reference, id(), correlation, detail, now());
    }
    void event(Connection c, String user, String name, String reference) throws SQLException {
        exec(c, "INSERT INTO events VALUES(?,?,?,?,?)", id(), user, name, reference, now());
    }
    void checkoutStarted(Connection c, String user, String key) throws SQLException {
        if (one(c, "SELECT id FROM events WHERE user_id=? AND name='checkout_started' AND reference_id=?", user, key) == null)
            event(c, user, "checkout_started", key);
    }
    public void startCheckout(String token, String key) {
        field(key, "[a-zA-Z0-9_-]{8,100}", "Identificador de checkout inválido.");
        store.tx(c -> {
            Map<String, Object> user = actor(c, token); active(user); role(user, "PAYER");
            checkoutStarted(c, text(user.get("id")), key);
            return null;
        });
    }
    void posting(Connection c, String payment, String from, String to, long amount, String phase) throws SQLException {
        exec(c, "INSERT INTO ledger VALUES(?,?,?,?,?,?)", id(), payment, from, -amount, phase, now());
        exec(c, "INSERT INTO ledger VALUES(?,?,?,?,?,?)", id(), payment, to, amount, phase, now());
    }
    long balance(Connection c, String user) throws SQLException {
        return number(one(c, "SELECT COALESCE(SUM(amount),0) AS total FROM ledger WHERE account=?", user).get("total"));
    }
    Map<String, Object> viewUser(Connection c, Map<String, Object> user) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>(user);
        result.put("balance", balance(c, text(user.get("id"))));
        result.put("reserved", number(one(c, "SELECT COALESCE(SUM(amount),0) AS total FROM payments WHERE payer_id=? AND status='PENDING'", user.get("id")).get("total")));
        result.put("demo", true);
        if (user.get("completed_at") == null) {
            boolean identity = one(c, "SELECT id FROM events WHERE user_id=? AND name='identity_step_completed'", user.get("id")) != null;
            boolean consent = one(c, "SELECT id FROM events WHERE user_id=? AND name='consent_step_completed'", user.get("id")) != null;
            result.put("onboarding_step", consent ? 3 : identity ? 2 : 1);
        }
        return result;
    }
    public Map<String, Object> me(String token) {
        return store.tx(c -> viewUser(c, actor(c, token)));
    }
    String session(Connection c, String user) throws SQLException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        exec(c, "DELETE FROM sessions WHERE expires_at<?", now());
        exec(c, "INSERT INTO sessions VALUES(?,?,?)", hash(token), user, Instant.now().plus(Duration.ofHours(8)).toString());
        return token;
    }
    public String login(String userId) {
        require(userId != null && userId.matches("[a-z][a-z0-9.-]{2,49}"), "DEMO_PROFILE", "Elige un perfil o alias de demostración válido.");
        return store.tx(c -> {
            if (Set.of("demo-ana", "demo-luis", "demo-merchant", "demo-ops").contains(userId)) return session(c, userId);
            Map<String, Object> user = one(c, "SELECT id FROM users WHERE alias=? AND completed_at IS NOT NULL", userId);
            if (user == null) throw new ApiError(404, "DEMO_PROFILE", "No encontramos ese alias de prueba. Completa primero el registro.");
            return session(c, text(user.get("id")));
        });
    }
    public void logout(String token) {
        store.tx(c -> { actor(c, token); exec(c, "DELETE FROM sessions WHERE token_hash=?", hash(token)); return null; });
    }
    public String start(OnboardingStart input) {
        String campaign = field(input.campaign(), "[a-zA-Z0-9_-]{1,40}", "La campaña requiere entre 1 y 40 letras, números o guiones.");
        return store.tx(c -> {
            String user = id();
            exec(c, "INSERT INTO users VALUES(?,?,?,?,?,?,?,?,?)", user, "Registro en progreso", "draft-" + user.substring(0, 12),
                    null, "PAYER", "PENDING", now(), null, campaign);
            event(c, user, "onboarding_started", user);
            return session(c, user);
        });
    }
    public Map<String, Object> complete(String token, OnboardingComplete input) {
        String name = clean(input.name(), 3, 80, "Escribe un nombre ficticio entre 3 y 80 caracteres.");
        String alias = field(input.alias(), "[a-z][a-z0-9.]{2,29}", "El alias debe comenzar con una letra y tener de 3 a 30 letras minúsculas, números o puntos.");
        String phone = field(input.phone(), "9[0-9]{8}", "Ingresa un celular ficticio peruano de 9 dígitos.");
        require(input.consent(), "CONSENT_REQUIRED", "Acepta el uso de datos sintéticos para continuar.");
        require(Set.of("APPROVED", "PENDING", "DECLINED").contains(text(input.kyc())), "KYC_SCENARIO", "Selecciona un resultado KYC de prueba.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            require(user.get("completed_at") == null, "ALREADY_COMPLETED", "Este onboarding ya fue completado.");
            require(one(c, "SELECT id FROM events WHERE user_id=? AND name='consent_step_completed'", user.get("id")) != null,
                    "STEPS_REQUIRED", "Completa identidad y consentimiento antes de finalizar.");
            Map<String, Object> duplicate = one(c, "SELECT id FROM users WHERE (alias=? OR phone=?) AND id<>?", alias, phone, user.get("id"));
            if (duplicate != null) throw new ApiError(409, "IDENTITY_EXISTS", "El alias o celular de prueba ya está registrado.");
            String status = switch (input.kyc()) { case "APPROVED" -> "ACTIVE"; case "DECLINED" -> "REJECTED"; default -> "PENDING"; };
            exec(c, "UPDATE users SET name=?,alias=?,phone=?,status=?,completed_at=? WHERE id=?", name, alias, phone, status, now(), user.get("id"));
            for (String step : List.of("kyc_step_completed", "onboarding_completed")) {
                event(c, text(user.get("id")), step, text(user.get("id")));
            }
            if (status.equals("ACTIVE")) fund(c, text(user.get("id")));
            audit(c, text(user.get("id")), "KYC_MOCK", text(user.get("id")), id(), status);
            return viewUser(c, one(c, "SELECT * FROM users WHERE id=?", user.get("id")));
        });
    }
    public Map<String, Object> onboardingStep(String token, OnboardingStep input) {
        require(Set.of("identity", "consent").contains(text(input.step())), "INVALID_STEP", "Paso de registro inválido.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            require(user.get("completed_at") == null, "ALREADY_COMPLETED", "El onboarding ya se completó.");
            if (input.step().equals("identity")) {
                String name = clean(input.name(), 3, 80, "Escribe un nombre ficticio de 3 a 80 caracteres.");
                String alias = field(input.alias(), "[a-z][a-z0-9.]{2,29}", "Alias inválido: usa 3–30 caracteres en minúsculas.");
                String phone = field(input.phone(), "9[0-9]{8}", "Usa un celular ficticio peruano de 9 dígitos.");
                require(one(c, "SELECT id FROM users WHERE (alias=? OR phone=?) AND id<>?", alias, phone, user.get("id")) == null,
                        "IDENTITY_EXISTS", "El alias o celular ya está registrado.");
                exec(c, "UPDATE users SET name=?,alias=?,phone=? WHERE id=?", name, alias, phone, user.get("id"));
            } else {
                require(input.consent(), "CONSENT_REQUIRED", "Acepta el uso exclusivo de datos sintéticos.");
                require(one(c, "SELECT id FROM events WHERE user_id=? AND name='identity_step_completed'", user.get("id")) != null,
                        "STEPS_REQUIRED", "Completa primero tu identidad ficticia.");
            }
            String eventName = input.step() + "_step_completed";
            if (one(c, "SELECT id FROM events WHERE user_id=? AND name=?", user.get("id"), eventName) == null)
                event(c, text(user.get("id")), eventName, text(user.get("id")));
            return viewUser(c, one(c, "SELECT * FROM users WHERE id=?", user.get("id")));
        });
    }
    void fund(Connection c, String user) throws SQLException {
        posting(c, null, "TREASURY", user, 50000L, "FUNDING");
        event(c, user, "wallet_activated", user);
    }
    public List<Map<String, Object>> contacts(String token) {
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            active(user);
            return rows(c, "SELECT id,name,alias,role FROM users WHERE status='ACTIVE' AND role<>'OPS' AND id<>? ORDER BY name LIMIT 100", user.get("id"));
        });
    }
    Map<String, Object> payment(Connection c, String paymentId) throws SQLException {
        Map<String, Object> p = one(c, "SELECT p.*,u.name AS destination_name,u.alias AS destination_alias,a.name AS payer_name FROM payments p JOIN users u ON u.id=p.destination_id JOIN users a ON a.id=p.payer_id WHERE p.id=?", paymentId);
        if (p == null) throw new ApiError(404, "PAYMENT_NOT_FOUND", "No encontramos esta transacción.");
        return p;
    }
    void access(Map<String, Object> user, Map<String, Object> p) {
        if (!user.get("role").equals("OPS") && !user.get("id").equals(p.get("payer_id")) && !user.get("id").equals(p.get("destination_id"))) {
            throw new ApiError(403, "FORBIDDEN", "No puedes consultar una transacción ajena.");
        }
    }
    Map<String, Object> publicPayment(Map<String, Object> payment) {
        Map<String, Object> result = new LinkedHashMap<>(payment);
        result.remove("fingerprint");
        result.remove("idem_key");
        result.put("retry_allowed", Set.of("FAILED", "EXPIRED").contains(text(payment.get("status"))));
        result.put("dispute_allowed", "SUCCEEDED".equals(payment.get("status")) && !Boolean.TRUE.equals(payment.get("reversed")));
        result.put("investigation_allowed", "PENDING".equals(payment.get("status")));
        return result;
    }
    public Map<String, Object> pay(String token, String key, PaymentInput input) {
        field(key, "[a-zA-Z0-9_-]{8,100}", "La clave de idempotencia debe tener entre 8 y 100 caracteres.");
        require(input.amount() != null && input.amount() > 0, "INVALID_AMOUNT", "Ingresa un monto positivo en céntimos.");
        require(Set.of("ALIAS", "QR").contains(text(input.channel())), "INVALID_CHANNEL", "Canal no permitido.");
        require(OUTCOMES.contains(text(input.scenario())), "INVALID_SCENARIO", "Escenario de proveedor no permitido.");
        require(input.amount() <= (input.channel().equals("QR") ? 15000L : 20000L), "PROVIDER_LIMIT",
                "Límite de demostración: S/ 200 por alias y S/ 150 por QR.");
        String fingerprint = String.join("|", text(input.destination()), input.amount().toString(), input.channel(), input.scenario(), text(input.qrId()));
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            active(user);
            role(user, "PAYER");
            Map<String, Object> original = one(c, "SELECT id,fingerprint FROM payments WHERE payer_id=? AND idem_key=?", user.get("id"), key);
            if (original != null) {
                if (!fingerprint.equals(original.get("fingerprint"))) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "La clave ya fue usada con otros datos. Consulta el intento original.");
                audit(c, text(user.get("id")), "DUPLICATE_REQUEST", text(original.get("id")), text(original.get("id")), "Original conservado; sin movimiento adicional");
                Map<String, Object> result = publicPayment(payment(c, text(original.get("id"))));
                result.put("duplicate", true);
                return result;
            }
            Map<String, Object> destination = one(c, "SELECT * FROM users WHERE alias=? AND status='ACTIVE' AND role<>'OPS'", input.destination());
            require(destination != null, "DESTINATION_NOT_FOUND", "Selecciona un destino activo.");
            require(!user.get("id").equals(destination.get("id")), "SELF_PAYMENT", "No puedes pagarte a ti mismo.");
            if (input.channel().equals("QR")) {
                Map<String, Object> qr = qr(c, input.qrId());
                require(qr.get("merchant_id").equals(destination.get("id")) && number(qr.get("amount")) == input.amount(),
                        "QR_MISMATCH", "El destino o monto no coincide con el QR.");
            } else require(input.qrId() == null || input.qrId().isBlank(), "INVALID_QR", "Un pago por alias no debe incluir QR.");
            require(balance(c, text(user.get("id"))) >= input.amount(), "INSUFFICIENT_FUNDS", "Saldo insuficiente. Elige un monto menor.");
            String pid = id();
            exec(c, "INSERT INTO payments(id,payer_id,destination_id,amount,channel,scenario,status,reason,idem_key,fingerprint,created_at,updated_at,reversed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", pid, user.get("id"), destination.get("id"), input.amount(),
                    input.channel(), input.scenario(), "PENDING", "AWAITING_PROVIDER", key, fingerprint, now(), now(), false);
            posting(c, pid, text(user.get("id")), "ESCROW", input.amount(), "RESERVE");
            checkoutStarted(c, text(user.get("id")), key);
            exec(c, "INSERT INTO provider_results VALUES(?,?,?,?,?)", pid, input.channel(), input.scenario(), input.amount(), now());
            settle(c, payment(c, pid), input.scenario());
            audit(c, text(user.get("id")), "PAYMENT_CREATED", pid, pid, input.channel() + ":" + input.scenario());
            return publicPayment(payment(c, pid));
        });
    }
    void settle(Connection c, Map<String, Object> p, String outcome) throws SQLException {
        if (outcome.equals("PENDING")) return;
        String state = switch (outcome) { case "APPROVED" -> "SUCCEEDED"; case "DECLINED" -> "FAILED"; case "TIMEOUT" -> "EXPIRED"; default -> throw new IllegalArgumentException("Unknown outcome"); };
        String reason = switch (outcome) { case "APPROVED" -> "PROVIDER_APPROVED"; case "DECLINED" -> "PROVIDER_DECLINED"; default -> "PROVIDER_TIMEOUT"; };
        String destination = outcome.equals("APPROVED") ? text(p.get("destination_id")) : text(p.get("payer_id"));
        posting(c, text(p.get("id")), "ESCROW", destination, number(p.get("amount")), "SETTLE");
        exec(c, "UPDATE payments SET status=?,reason=?,updated_at=? WHERE id=?", state, reason, now(), p.get("id"));
        event(c, text(p.get("payer_id")), "checkout_" + state.toLowerCase(Locale.ROOT), text(p.get("id")));
        if (state.equals("SUCCEEDED")) event(c, text(p.get("payer_id")), "payer_activated", text(p.get("id")));
    }
    public Map<String, Object> callback(String token, String paymentId, CallbackInput input) {
        field(input.eventId(), "[a-zA-Z0-9_-]{8,100}", "Identificador de evento inválido.");
        require(OUTCOMES.contains(text(input.outcome())), "INVALID_OUTCOME", "Resultado no permitido.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            role(user, "OPS");
            Map<String, Object> p = payment(c, paymentId);
            String fingerprint = paymentId + "|" + input.outcome();
            Map<String, Object> previous = one(c, "SELECT * FROM callbacks WHERE event_id=?", input.eventId());
            if (previous != null) {
                if (!fingerprint.equals(previous.get("fingerprint"))) throw new ApiError(409, "CALLBACK_CONFLICT", "El identificador ya pertenece a otro evento.");
                audit(c, text(user.get("id")), "DUPLICATE_CALLBACK", paymentId, paymentId, input.eventId());
                return publicPayment(p);
            }
            String expected = switch (text(p.get("status"))) { case "SUCCEEDED" -> "APPROVED"; case "FAILED" -> "DECLINED"; case "EXPIRED" -> "TIMEOUT"; default -> "PENDING"; };
            if (TERMINALS.contains(text(p.get("status"))) && !expected.equals(input.outcome())) {
                throw new ApiError(409, "TERMINAL_CONFLICT", "Un resultado terminal no se sobrescribe. Escala a investigación y conciliación.");
            }
            exec(c, "INSERT INTO callbacks VALUES(?,?,?,?)", input.eventId(), paymentId, fingerprint, now());
            exec(c, "MERGE INTO provider_results KEY(payment_id) VALUES(?,?,?,?,?)", paymentId, p.get("channel"), input.outcome(), p.get("amount"), now());
            if (p.get("status").equals("PENDING")) settle(c, p, input.outcome());
            audit(c, text(user.get("id")), "MOCK_CALLBACK", paymentId, paymentId, input.outcome());
            return publicPayment(payment(c, paymentId));
        });
    }
    public Map<String, Object> getPayment(String token, String paymentId) {
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token), p = payment(c, paymentId);
            access(user, p);
            Map<String, Object> result = publicPayment(p);
            result.put("ledger", rows(c, "SELECT account,amount,phase,created_at FROM ledger WHERE payment_id=? ORDER BY created_at", paymentId));
            result.put("cases", rows(c, "SELECT * FROM cases WHERE payment_id=? ORDER BY created_at DESC", paymentId));
            return result;
        });
    }
    static LocalDate date(String value) {
        try { return LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException error) { throw new ApiError(400, "INVALID_DATE", "Usa una fecha válida YYYY-MM-DD."); }
    }
    boolean inDate(Map<String, Object> row, String date) {
        return date == null || date.isBlank() || Instant.parse(text(row.get("created_at"))).atZone(LIMA).toLocalDate().equals(date(date));
    }
    public List<Map<String, Object>> history(String token, String day, String reference, String channel) {
        if (day != null && !day.isBlank()) date(day);
        require(reference == null || reference.length() <= 50, "INVALID_REFERENCE", "Referencia demasiado larga.");
        require(channel == null || channel.isBlank() || Set.of("ALIAS", "QR").contains(channel), "INVALID_CHANNEL", "Canal inválido.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            String uid = text(user.get("id"));
            List<Map<String, Object>> result = rows(c, "SELECT p.*,u.name AS destination_name,u.alias AS destination_alias,a.name AS payer_name FROM payments p JOIN users u ON u.id=p.destination_id JOIN users a ON a.id=p.payer_id WHERE (?='OPS' OR p.payer_id=? OR p.destination_id=?) ORDER BY p.created_at DESC", user.get("role"), uid, uid);
            return result.stream().filter(p -> inDate(p, day))
                    .filter(p -> reference == null || reference.isBlank() || text(p.get("id")).contains(reference))
                    .filter(p -> channel == null || channel.isBlank() || channel.equals(p.get("channel")))
                    .limit(200).map(this::publicPayment).toList();
        });
    }
    Map<String, Object> qr(Connection c, String qrId) throws SQLException {
        Map<String, Object> qr = one(c, "SELECT q.*,u.name,u.alias FROM qr_codes q JOIN users u ON u.id=q.merchant_id WHERE q.id=?", qrId);
        if (qr == null) throw new ApiError(404, "QR_NOT_FOUND", "El QR no pertenece a esta demo.");
        require(Instant.parse(text(qr.get("expires_at"))).isAfter(Instant.now()), "QR_EXPIRED", "El QR expiró. Pide uno nuevo al comercio.");
        return qr;
    }
    public Map<String, Object> createQr(String token, long amount) {
        require(amount > 0 && amount <= 15000L, "QR_LIMIT", "El QR acepta de S/ 0.01 a S/ 150.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            role(user, "MERCHANT"); active(user);
            String qid = id();
            exec(c, "INSERT INTO qr_codes VALUES(?,?,?,?)", qid, user.get("id"), amount, Instant.now().plus(Duration.ofMinutes(15)).toString());
            audit(c, text(user.get("id")), "QR_CREATED", qid, qid, Long.toString(amount));
            return qr(c, qid);
        });
    }
    public Map<String, Object> readQr(String token, String qrId) {
        return store.tx(c -> { active(actor(c, token)); return qr(c, qrId); });
    }
    public Map<String, Object> openCase(String token, String pid, CaseInput input) {
        require(Set.of("TECHNICAL", "DISPUTE", "REVERSAL").contains(text(input.category())), "CASE_CATEGORY", "Categoría no permitida.");
        String note = clean(input.note(), 5, 400, "Describe el caso entre 5 y 400 caracteres, sin datos personales.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token), p = payment(c, pid);
            access(user, p);
            if (!input.category().equals("TECHNICAL")) require(p.get("status").equals("SUCCEEDED") && !Boolean.TRUE.equals(p.get("reversed")),
                    "NOT_DISPUTABLE", "Solo los pagos ejecutados y no revertidos admiten disputa o solicitud de reverso.");
            Map<String, Object> existing = one(c, "SELECT id FROM cases WHERE payment_id=? AND category=? AND status='OPEN'", pid, input.category());
            if (existing != null) throw new ApiError(409, "CASE_EXISTS", "Ya existe un caso abierto de esta categoría.");
            String cid = id();
            exec(c, "INSERT INTO cases VALUES(?,?,?,?,?,?,?,?)", cid, pid, input.category(), note, "OPEN", null, now(), now());
            audit(c, text(user.get("id")), "CASE_OPENED", pid, cid, input.category());
            return one(c, "SELECT * FROM cases WHERE id=?", cid);
        });
    }
    public List<Map<String, Object>> cases(String token) {
        return store.tx(c -> { role(actor(c, token), "OPS"); return rows(c, "SELECT * FROM cases ORDER BY created_at DESC LIMIT 200"); });
    }
    public Map<String, Object> decideCase(String token, String caseId, CaseDecision input) {
        require(Set.of("APPROVE_REVERSAL", "REJECT", "RESOLVE").contains(text(input.decision())), "INVALID_DECISION", "Decisión inválida.");
        String note = clean(input.note(), 5, 400, "Registra una resolución de 5 a 400 caracteres.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token); role(user, "OPS");
            Map<String, Object> support = one(c, "SELECT * FROM cases WHERE id=?", caseId);
            if (support == null) throw new ApiError(404, "CASE_NOT_FOUND", "Caso no encontrado.");
            require(support.get("status").equals("OPEN"), "CASE_CLOSED", "El caso ya está cerrado.");
            Map<String, Object> p = payment(c, text(support.get("payment_id")));
            if (input.decision().equals("APPROVE_REVERSAL")) {
                require(support.get("category").equals("REVERSAL") && p.get("status").equals("SUCCEEDED") && !Boolean.TRUE.equals(p.get("reversed")),
                        "REVERSAL_NOT_ALLOWED", "El caso no admite reverso. No es posible cancelar unilateralmente un P2P.");
                require(balance(c, text(p.get("destination_id"))) >= number(p.get("amount")), "REVERSAL_FUNDS", "El destino no tiene saldo suficiente. Escala para investigación.");
                posting(c, text(p.get("id")), text(p.get("destination_id")), text(p.get("payer_id")), number(p.get("amount")), "REVERSAL");
                exec(c, "UPDATE payments SET reversed=TRUE,updated_at=? WHERE id=?", now(), p.get("id"));
            }
            exec(c, "UPDATE cases SET status=?,resolution=?,updated_at=? WHERE id=?",
                    input.decision().equals("REJECT") ? "REJECTED" : "RESOLVED", note, now(), caseId);
            audit(c, text(user.get("id")), input.decision(), text(p.get("id")), caseId, "Decisión humana registrada; reverso externo simulado");
            return one(c, "SELECT * FROM cases WHERE id=?", caseId);
        });
    }
    public List<Map<String, Object>> onboardingQueue(String token) {
        return store.tx(c -> { role(actor(c, token), "OPS"); return rows(c, "SELECT id,name,alias,status FROM users WHERE status='PENDING' AND completed_at IS NOT NULL ORDER BY created_at LIMIT 200"); });
    }
    public Map<String, Object> reviewKyc(String token, String userId, String decision) {
        require(Set.of("APPROVED", "DECLINED").contains(text(decision)), "INVALID_DECISION", "Decisión KYC inválida.");
        return store.tx(c -> {
            Map<String, Object> ops = actor(c, token); role(ops, "OPS");
            Map<String, Object> user = one(c, "SELECT * FROM users WHERE id=?", userId);
            require(user != null && user.get("status").equals("PENDING") && user.get("completed_at") != null, "KYC_NOT_PENDING", "La cuenta no está pendiente de revisión.");
            exec(c, "UPDATE users SET status=? WHERE id=?", decision.equals("APPROVED") ? "ACTIVE" : "REJECTED", userId);
            if (decision.equals("APPROVED")) fund(c, userId);
            audit(c, text(ops.get("id")), "HUMAN_KYC_" + decision, userId, userId, "Identidad sintética; sin verificación real");
            return viewUser(c, one(c, "SELECT * FROM users WHERE id=?", userId));
        });
    }
    public void fixture(String token, String pid, FixtureInput input) {
        require(input.missing() || (OUTCOMES.contains(text(input.outcome())) && input.amount() != null && input.amount() > 0 && input.amount() <= 20000),
                "INVALID_FIXTURE", "Fixture inválido: selecciona estado y monto hasta S/ 200.");
        store.tx(c -> {
            Map<String, Object> user = actor(c, token); role(user, "OPS");
            Map<String, Object> p = payment(c, pid);
            if (input.missing()) exec(c, "DELETE FROM provider_results WHERE payment_id=?", pid);
            else exec(c, "MERGE INTO provider_results KEY(payment_id) VALUES(?,?,?,?,?)", pid, p.get("channel"), input.outcome(), input.amount(), now());
            audit(c, text(user.get("id")), "PROVIDER_FIXTURE", pid, pid, input.missing() ? "MISSING" : input.outcome());
            return null;
        });
    }
    public Map<String, Object> reconcile(String token, String day, String channel) {
        require(day != null && !day.isBlank(), "DATE_REQUIRED", "Selecciona el día a conciliar (hora Perú).");
        date(day);
        require(channel == null || channel.isBlank() || Set.of("ALIAS", "QR").contains(channel), "INVALID_CHANNEL", "Canal inválido.");
        return store.tx(c -> {
            Map<String, Object> ops = actor(c, token); role(ops, "OPS");
            List<Map<String, Object>> selected = rows(c, "SELECT * FROM payments ORDER BY created_at").stream()
                    .filter(p -> inDate(p, day)).filter(p -> channel == null || channel.isBlank() || channel.equals(p.get("channel"))).toList();
            List<Map<String, Object>> report = new ArrayList<>();
            int matched = 0, unresolved = 0, resolved = 0;
            for (Map<String, Object> p : selected) {
                String pid = text(p.get("id"));
                Map<String, Object> provider = one(c, "SELECT * FROM provider_results WHERE payment_id=?", pid);
                Set<String> kinds = new LinkedHashSet<>();
                if (provider == null) kinds.add("MISSING_PROVIDER");
                else {
                    if (number(provider.get("amount")) != number(p.get("amount"))) kinds.add("AMOUNT_MISMATCH");
                    String expected = switch (text(p.get("status"))) { case "SUCCEEDED" -> "APPROVED"; case "FAILED" -> "DECLINED"; case "EXPIRED" -> "TIMEOUT"; default -> "PENDING"; };
                    if (!expected.equals(provider.get("status"))) kinds.add("STATUS_MISMATCH");
                }
                if (p.get("status").equals("PENDING")) kinds.add("PENDING_REVIEW");
                long amount = number(p.get("amount"));
                Map<String, Long> expectedLedger = new HashMap<>();
                expectedLedger.put(p.get("payer_id") + ":RESERVE", -amount);
                expectedLedger.put("ESCROW:RESERVE", amount);
                if (!p.get("status").equals("PENDING")) {
                    expectedLedger.put("ESCROW:SETTLE", -amount);
                    String target = text(p.get(p.get("status").equals("SUCCEEDED") ? "destination_id" : "payer_id"));
                    expectedLedger.put(target + ":SETTLE", amount);
                }
                if (Boolean.TRUE.equals(p.get("reversed"))) {
                    expectedLedger.put(p.get("destination_id") + ":REVERSAL", -amount);
                    expectedLedger.put(p.get("payer_id") + ":REVERSAL", amount);
                }
                Map<String, Long> actualLedger = new HashMap<>();
                for (Map<String, Object> entry : rows(c, "SELECT account,phase,amount FROM ledger WHERE payment_id=?", pid)) {
                    actualLedger.merge(entry.get("account") + ":" + entry.get("phase"), number(entry.get("amount")), Long::sum);
                }
                if (!expectedLedger.equals(actualLedger)) kinds.add("LEDGER_MISMATCH");
                for (String kind : kinds) {
                    Map<String, Object> existing = one(c, "SELECT * FROM mismatches WHERE payment_id=? AND kind=?", pid, kind);
                    if (existing == null) exec(c, "INSERT INTO mismatches VALUES(?,?,?,?,?,?)", id(), pid, kind, "OPEN", now(), now());
                    else if (existing.get("status").equals("RESOLVED")) exec(c, "UPDATE mismatches SET status='OPEN',updated_at=? WHERE id=?", now(), existing.get("id"));
                }
                for (Map<String, Object> mismatch : rows(c, "SELECT * FROM mismatches WHERE payment_id=?", pid)) {
                    if (!kinds.contains(text(mismatch.get("kind")))) exec(c, "UPDATE mismatches SET status='RESOLVED',updated_at=? WHERE id=?", now(), mismatch.get("id"));
                }
                List<Map<String, Object>> issues = rows(c, "SELECT * FROM mismatches WHERE payment_id=?", pid);
                if (kinds.isEmpty()) matched++; else unresolved++;
                if (kinds.isEmpty() && !issues.isEmpty()) resolved++;
                long duplicates = number(one(c, "SELECT COUNT(*) AS total FROM audit WHERE reference_id=? AND action='DUPLICATE_REQUEST'", pid).get("total"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("payment", publicPayment(payment(c, pid)));
                row.put("provider", provider);
                row.put("issues", issues);
                row.put("matched", kinds.isEmpty());
                row.put("duplicate_requests", duplicates);
                report.add(row);
            }
            audit(c, text(ops.get("id")), "RECONCILIATION_RUN", day, id(), "Canal:" + text(channel));
            return Map.of("date", day, "timezone", "America/Lima", "matched", matched, "unmatched", unresolved,
                    "resolved", resolved, "total", selected.size(), "rows", report);
        });
    }
    public void investigate(String token, String mismatchId) {
        store.tx(c -> {
            Map<String, Object> user = actor(c, token); role(user, "OPS");
            Map<String, Object> m = one(c, "SELECT * FROM mismatches WHERE id=?", mismatchId);
            if (m == null) throw new ApiError(404, "MISMATCH_NOT_FOUND", "Descalce no encontrado.");
            require(!m.get("status").equals("RESOLVED"), "ALREADY_RESOLVED", "El descalce ya está resuelto.");
            exec(c, "UPDATE mismatches SET status='INVESTIGATING',updated_at=? WHERE id=?", now(), mismatchId);
            audit(c, text(user.get("id")), "INVESTIGATING", text(m.get("payment_id")), mismatchId, "Asignado a revisión humana");
            return null;
        });
    }
    public List<Map<String, Object>> auditLog(String token) {
        return store.tx(c -> { role(actor(c, token), "OPS"); return rows(c, "SELECT * FROM audit ORDER BY created_at DESC LIMIT 200"); });
    }
    public void acquisition(String token, AcquisitionInput input) {
        field(input.month(), "[0-9]{4}-(0[1-9]|1[0-2])", "Usa un mes YYYY-MM.");
        field(input.campaign(), "[a-zA-Z0-9_-]{1,40}", "Campaña inválida.");
        require(input.cents() != null && input.cents() >= 0 && input.cents() <= 100000000L, "INVALID_SPEND", "Gasto inválido en céntimos.");
        store.tx(c -> {
            Map<String, Object> user = actor(c, token); role(user, "OPS");
            exec(c, "MERGE INTO acquisition KEY(month_key,campaign) VALUES(?,?,?)", input.month(), input.campaign(), input.cents());
            audit(c, text(user.get("id")), "ACQUISITION_SPEND", input.month(), id(), input.campaign() + ":" + input.cents());
            return null;
        });
    }
    public Map<String, Object> metrics(String token, String launchMonth) {
        field(launchMonth, "[0-9]{4}-(0[1-9]|1[0-2])", "Usa un mes YYYY-MM.");
        return store.tx(c -> {
            role(actor(c, token), "OPS");
            List<Map<String, Object>> events = rows(c, "SELECT * FROM events ORDER BY created_at");
            List<Map<String, Object>> users = rows(c, "SELECT * FROM users");
            List<Map<String, Object>> spends = rows(c, "SELECT month_key AS \"month\",campaign,cents FROM acquisition");
            Set<String> successfulAttempts = new HashSet<>();
            for (Map<String, Object> p : rows(c, "SELECT payer_id,idem_key FROM payments WHERE status='SUCCEEDED'"))
                successfulAttempts.add(p.get("payer_id") + "|" + p.get("idem_key"));
            List<Map<String, Object>> months = new ArrayList<>();
            YearMonth launch = YearMonth.parse(launchMonth);
            for (int n = 0; n < 3; n++) {
                String month = launch.plusMonths(n).toString();
                List<Map<String, Object>> ev = events.stream().filter(e -> monthOf(e.get("created_at")).equals(month)).toList();
                long starts = ev.stream().filter(e -> e.get("name").equals("checkout_started")).count();
                long successes = ev.stream().filter(e -> e.get("name").equals("checkout_started")
                        && successfulAttempts.contains(e.get("user_id") + "|" + e.get("reference_id"))).count();
                long mau = ev.stream().filter(e -> e.get("name").equals("payer_activated")).map(e -> e.get("user_id")).distinct().count();
                long newUsers = ev.stream().filter(e -> e.get("name").equals("wallet_activated")).map(e -> e.get("user_id")).distinct().count();
                List<Long> durations = users.stream().filter(u -> u.get("completed_at") != null && !u.get("campaign").equals("demo") && monthOf(u.get("completed_at")).equals(month))
                        .map(u -> Duration.between(Instant.parse(text(u.get("created_at"))), Instant.parse(text(u.get("completed_at")))).toSeconds()).sorted().toList();
                Double median = durations.isEmpty() ? null : durations.size() % 2 == 1 ? durations.get(durations.size() / 2).doubleValue()
                        : (durations.get(durations.size() / 2 - 1) + durations.get(durations.size() / 2)) / 2.0;
                long spend = spends.stream().filter(s -> s.get("month").equals(month)).mapToLong(s -> number(s.get("cents"))).sum();
                boolean recordedSpend = spends.stream().anyMatch(s -> s.get("month").equals(month));
                Map<String, String> campaigns = new HashMap<>();
                for (Map<String, Object> u : users) campaigns.put(text(u.get("id")), text(u.get("campaign")));
                Set<String> campaignNames = new TreeSet<>();
                spends.stream().filter(s -> s.get("month").equals(month)).forEach(s -> campaignNames.add(text(s.get("campaign"))));
                ev.stream().filter(e -> e.get("name").equals("wallet_activated")).forEach(e -> campaignNames.add(campaigns.get(text(e.get("user_id")))));
                List<Map<String, Object>> attribution = new ArrayList<>();
                for (String campaign : campaignNames) {
                    long activated = ev.stream().filter(e -> e.get("name").equals("wallet_activated") && campaign.equals(campaigns.get(text(e.get("user_id")))))
                            .map(e -> e.get("user_id")).distinct().count();
                    var campaignSpend = spends.stream().filter(s -> s.get("month").equals(month) && s.get("campaign").equals(campaign)).toList();
                    long cost = campaignSpend.stream().mapToLong(s -> number(s.get("cents"))).sum();
                    Map<String, Object> part = new LinkedHashMap<>();
                    part.put("campaign", campaign); part.put("new_active_users", activated);
                    part.put("spend_cents", campaignSpend.isEmpty() ? null : cost);
                    part.put("cac_cents", activated == 0 || campaignSpend.isEmpty() ? null : BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(activated), 2, java.math.RoundingMode.HALF_UP));
                    attribution.add(part);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("month", month); row.put("checkout_started", starts); row.put("checkout_succeeded", successes);
                row.put("dropoff_percent", starts == 0 ? null : 100.0 * (starts - successes) / starts);
                row.put("mau", mau); row.put("new_active_users", newUsers); row.put("spend_cents", spend);
                row.put("cac_cents", newUsers == 0 || !recordedSpend ? null : BigDecimal.valueOf(spend).divide(BigDecimal.valueOf(newUsers), 2, java.math.RoundingMode.HALF_UP));
                row.put("onboarding_median_seconds", median);
                row.put("attribution", attribution);
                months.add(row);
            }
            return Map.of("months", months, "acquisition", spends, "targets", Map.of("dropoff_percent", 8, "onboarding_seconds", 180, "mau_min", 1000, "mau_max", 2000, "cac_cents", 1000),
                    "notice", "Datos sintéticos. Los objetivos de negocio no se consideran alcanzados por una demo.");
        });
    }
    static String monthOf(Object timestamp) { return YearMonth.from(Instant.parse(text(timestamp)).atZone(LIMA)).toString(); }
    public Map<String, Object> assistant(String token, AgentInput input) {
        String message = clean(input.message(), 1, 500, "Escribe una consulta entre 1 y 500 caracteres.");
        return store.tx(c -> {
            Map<String, Object> user = actor(c, token);
            String lower = message.toLowerCase(Locale.ROOT);
            boolean blocked = List.of("ignora", "ignore", "system prompt", "secret", "token", "ejecuta", "sql", "contraseña").stream().anyMatch(lower::contains);
            String guide;
            String intent;
            if (blocked) {
                intent = "GUARDRAIL_BLOCK";
                guide = "No puedo ejecutar instrucciones, revelar datos ni cambiar saldos. Solo explico operaciones autorizadas y recomiendo revisión humana.";
            } else if (input.paymentId() != null && !input.paymentId().isBlank()) {
                Map<String, Object> p = payment(c, input.paymentId()); access(user, p);
                intent = "EXPLAIN_PAYMENT";
                guide = Boolean.TRUE.equals(p.get("reversed")) ? "El pago fue exitoso y luego tuvo un reverso simulado aprobado por operaciones. Ambos movimientos están en el ledger."
                        : switch (text(p.get("status"))) {
                    case "SUCCEEDED" -> "Pago ejecutado. El comprobante es definitivo para este intento. No se puede cancelar unilateralmente: solicita una disputa o reverso para evaluación humana.";
                    case "FAILED" -> "El proveedor rechazó el pago. La reserva se devolvió a tu saldo. Puedes iniciar un nuevo intento; reenviar la misma clave solo consulta el original.";
                    case "EXPIRED" -> "El proveedor mock confirmó expiración y liberó la reserva. En producción un timeout de red requeriría investigación antes de liberar fondos.";
                    default -> "Aún no hay confirmación. El monto está reservado: no entregues productos ni repitas el pago con otra clave. Operaciones puede investigar o simular un callback.";
                };
            } else {
                intent = lower.contains("qr") ? "QR_GUIDE" : lower.contains("revers") ? "HUMAN_REVIEW" : "ONBOARDING_GUIDE";
                guide = switch (intent) {
                    case "QR_GUIDE" -> "Entra como comercio, genera un QR de hasta S/ 150 y descarga el PNG. Como pagador, carga ese archivo, revisa el nombre y monto y confirma. Vence en 15 minutos.";
                    case "HUMAN_REVIEW" -> "Un reverso requiere un pago exitoso, una solicitud explícita y aprobación humana de operaciones. El asistente nunca mueve dinero.";
                    default -> "Usa solo datos ficticios. Completa identidad, consentimiento y KYC mock. Una wallet activa recibe S/ 500 de prueba; puedes pagar hasta S/ 200 por alias o S/ 150 por QR.";
                };
            }
            String trace = id();
            audit(c, text(user.get("id")), "ASSISTANT_" + intent, input.paymentId(), trace, "Policy v1; read-only; sin entrada libre en logs");
            return Map.of("message", guide, "intent", intent, "trace_id", trace, "mode", "LOCAL_RULE_BASED", "human_review_required", intent.equals("HUMAN_REVIEW"), "tools", List.of("authorized_payment_read", "policy_guide"));
        });
    }
}
