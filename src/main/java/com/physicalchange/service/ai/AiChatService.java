package com.physicalchange.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.service.ClientDirectory;
import com.physicalchange.util.DateTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Panel de conversación de Physical AI (docs/api-ia.md, ADR-003 §9–§10). El modelo no tiene herramientas: el backend detecta la
 * sugerencia o las @menciones, consulta él mismo los datos del profesional autenticado y se los pasa resumidos y seudonimizados
 * ("Cliente 1"…); en la respuesta se vuelven a poner los nombres. Nunca se envían emails, teléfonos, nacimiento ni datos de salud.
 * Las conversaciones se borran a los 30 días.
 */
@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final int KEEP_DAYS = 30, HISTORY = 6;
    private static final Pattern PSEUDO = Pattern.compile("\\bCliente (\\d+)\\b");

    private final AiService ai;
    private final AiProvider provider;
    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final UserRepository users;
    private final ClientProfileRepository profiles;
    private final ClientTagRepository tags;
    private final WorkoutAssignmentRepository assignments;
    private final ExerciseLogRepository logs;
    private final AgendaEventRepository events;
    private final AgendaBookingRepository bookings;
    private final ClientDirectory directory;
    private final AccessService access;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public AiChatService(AiService a, AiProvider p, AiConversationRepository c, AiMessageRepository m, UserRepository u,
                         ClientProfileRepository pr, ClientTagRepository t, WorkoutAssignmentRepository w, ExerciseLogRepository l,
                         AgendaEventRepository e, AgendaBookingRepository b, ClientDirectory d, AccessService ac, ObjectMapper j,
                         PlatformTransactionManager tm) {
        ai = a; provider = p; conversations = c; messages = m; users = u; profiles = pr; tags = t; assignments = w; logs = l; events = e;
        bookings = b; directory = d; access = ac; json = j; tx = new TransactionTemplate(tm);
    }

    public List<AiSuggestionDto> suggestions() {
        return List.of(
                new AiSuggestionDto("CLIENT_REPORT", "Genera un reporte para un cliente", "Métricas, adherencia y próximos pasos"),
                new AiSuggestionDto("ACTIVITY_SUMMARY", "Resumen de actividad", "Sesiones, adherencia y pendientes"),
                new AiSuggestionDto("INACTIVE_7_DAYS", "¿Quién no ha entrenado en 7 días?", "Detecta inactividad al instante"),
                new AiSuggestionDto("WHAT_CAN_YOU_DO", "¿Qué sabes hacer?", "Descubre todo lo que puede hacer"));
    }

    // ---------- Conversaciones ----------

    @Transactional
    public List<AiConversationDto> list() {
        purge();
        return conversations.findByTrainerIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(access.current().id(), LocalDateTime.now().minusDays(KEEP_DAYS))
                .stream().map(AiChatService::toDto).toList();
    }

    @Transactional
    public AiConversationDto create(String context) {
        AiConversation c = new AiConversation();
        c.setTrainerId(access.current().id());
        c.setTitle("Nueva conversación");
        c.setContext(context == null || context.isBlank() ? null : AiService.cut(context.strip(), 60));
        return toDto(conversations.save(c));
    }

    @Transactional(readOnly = true)
    public AiConversationDetailDto get(Long id) {
        AiConversation c = owned(id);
        return new AiConversationDetailDto(toDto(c), messages.findByConversationIdOrderByCreatedAtAscIdAsc(id).stream().map(this::toDto).toList());
    }

    @Transactional
    public void delete(Long id) {
        AiConversation c = owned(id);
        messages.deleteByConversationIdIn(List.of(id));
        conversations.delete(c);
    }

    /** Borra las conversaciones (y mensajes) de más de 30 días. También al arrancar. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void purge() {
        List<Long> old = conversations.findByUpdatedAtBefore(LocalDateTime.now().minusDays(KEEP_DAYS)).stream().map(AiConversation::getId).toList();
        if (old.isEmpty()) return;
        messages.deleteByConversationIdIn(old);
        conversations.deleteAllById(old);
        log.info("Physical AI: {} conversaciones antiguas borradas", old.size());
    }

    private AiConversation owned(Long id) {
        return conversations.findById(id).filter(c -> c.getTrainerId().equals(access.current().id()))
                .orElseThrow(() -> new NotFoundException("Conversación no encontrada"));
    }

    // ---------- Mensajes ----------

    public AiSendMessageResponse send(Long conversationId, AiSendMessageRequest r) {
        AuthUser me = access.current();
        AiConversation conv = tx.execute(st -> owned(conversationId));
        if (ai.chatToday(me.id()) >= ai.chatLimit())
            throw new AiException(429, "Has llegado al límite de " + ai.chatLimit() + " mensajes de hoy", AiService.secondsToMidnight());
        ai.ensureReady();

        // Clientes propios: seudónimos estables por id (Cliente 1, Cliente 2…).
        List<AppUser> own = directory.clientsOf(me.id()).stream().sorted(Comparator.comparing(AppUser::getId)).toList();
        Map<Long, String> pseudo = new LinkedHashMap<>();
        for (int i = 0; i < own.size(); i++) pseudo.put(own.get(i).getId(), "Cliente " + (i + 1));
        Map<String, String> realByPseudo = own.stream().collect(Collectors.toMap(u -> pseudo.get(u.getId()), AppUser::getFullName));

        List<Long> mentionIds = r.mentionClientIds() == null ? List.of() : r.mentionClientIds().stream().distinct().toList();
        List<AiMentionDto> mentions = new ArrayList<>();
        for (Long id : mentionIds) {
            AppUser c = own.stream().filter(u -> u.getId().equals(id)).findFirst().orElseThrow(() -> new ForbiddenException("No es tu cliente"));
            mentions.add(new AiMentionDto(c.getId(), c.getFullName()));
        }

        String data = data(r.suggestion(), mentionIds, own, pseudo);
        String system = ai.system("Respondes preguntas sobre su negocio y sus clientes en un panel de chat. Respuestas breves, en texto plano "
                + "(sin HTML); puedes usar **negrita** y listas con guiones.", me.id())
                + "\nLos clientes se llaman por su seudónimo (\"Cliente N\"). Usa solo los DATOS de abajo; si no bastan, dilo."
                + "\nSabes crear workouts y programas con los asistentes de la Librería; planes diarios y recetas llegarán más adelante."
                + (data.isBlank() ? "" : "\nDATOS (consultados por Physical Change):\n" + data);

        List<AiProvider.Turn> turns = new ArrayList<>();
        List<AiMessage> history = messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        for (AiMessage m : history.subList(Math.max(0, history.size() - HISTORY), history.size())) {
            turns.add(new AiProvider.Turn("USER".equals(m.getRole()) ? "user" : "assistant", hide(AiService.cut(m.getText(), 2000), own, pseudo)));
        }
        String userText = r.text().strip();
        String sent = hide(userText, own, pseudo) + (mentions.isEmpty() ? "" : "\nMenciona a: "
                + mentionIds.stream().map(pseudo::get).collect(Collectors.joining(", ")));
        turns.add(new AiProvider.Turn("user", sent));

        AiProvider.Completion c = provider.complete(system, turns, null, 700);
        String answer = AiService.cut(reveal(c.text() == null ? "" : c.text().strip(), realByPseudo), 8000);
        if (answer.isBlank()) throw new AiException(422, "La IA no devolvió respuesta. Inténtalo de nuevo");

        return tx.execute(st -> {
            AiConversation fresh = owned(conv.getId());
            AiMessage u = message(fresh.getId(), "USER", userText, mentions);
            AiMessage a = message(fresh.getId(), "ASSISTANT", answer, List.of());
            if ("Nueva conversación".equals(fresh.getTitle())) fresh.setTitle(AiService.cut(userText, 60));
            fresh.touch();
            ai.record(me.id(), "CHAT", 0, c, null, null);
            return new AiSendMessageResponse(toDto(u), toDto(a));
        });
    }

    private AiMessage message(Long conversationId, String role, String text, List<AiMentionDto> mentions) {
        AiMessage m = new AiMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setText(text);
        try { m.setMentions(json.writeValueAsString(mentions)); } catch (Exception e) { m.setMentions("[]"); }
        return messages.save(m);
    }

    /** Datos que el backend añade según la sugerencia o las menciones (solo del profesional autenticado). */
    private String data(String suggestion, List<Long> mentionIds, List<AppUser> own, Map<Long, String> pseudo) {
        if (own.isEmpty()) return "El profesional todavía no tiene clientes.";
        LocalDate today = LocalDate.now();
        List<Long> ids = own.stream().filter(AppUser::isActive).map(AppUser::getId).toList();
        Map<Long, LocalDate> last = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : logs.lastLogDates(ids)) last.merge((Long) row[0], (LocalDate) row[1], (x, y) -> x.isAfter(y) ? x : y);
            for (Object[] row : assignments.lastCompletedDates(ids)) last.merge((Long) row[0], (LocalDate) row[1], (x, y) -> x.isAfter(y) ? x : y);
        }
        StringBuilder sb = new StringBuilder();
        if ("INACTIVE_7_DAYS".equals(suggestion)) {
            sb.append("Clientes activos sin actividad en los últimos 7 días:\n");
            boolean any = false;
            for (Long id : ids) {
                LocalDate l = last.get(id);
                if (l == null || l.isBefore(today.minusDays(7))) {
                    any = true;
                    sb.append("- ").append(pseudo.get(id)).append(": ").append(l == null ? "sin actividad registrada" : "última actividad hace "
                            + ChronoUnit.DAYS.between(l, today) + " días").append('\n');
                }
            }
            if (!any) sb.append("- Ninguno: todos han entrenado esta semana.\n");
        }
        if ("ACTIVITY_SUMMARY".equals(suggestion)) {
            List<WorkoutAssignment> week = ids.isEmpty() ? List.of() : assignments.findByClientIdInAndScheduledDateBetween(ids, today.minusDays(6), today);
            long done = week.stream().filter(w -> w.getCompletedDate() != null).count();
            List<AgendaEvent> sessions = events.findByTrainerIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                    access.current().id(), DateTimes.startOfDay(today.minusDays(6)), DateTimes.endExclusive(today.plusDays(7))).stream()
                    .filter(e -> "SESSION".equals(e.getKind())).toList();
            long past = sessions.stream().filter(e -> e.getStartAt().toLocalDate().isBefore(today.plusDays(1))).count();
            sb.append("Últimos 7 días: ").append(ids.size()).append(" clientes activos; workouts asignados ").append(week.size())
              .append(", completados ").append(done).append(week.isEmpty() ? "" : " (" + Math.round(done * 100.0 / week.size()) + " %)")
              .append("; sesiones de agenda ").append(past).append(". Próximos 7 días: ").append(sessions.size() - past).append(" sesiones.\n");
        }
        List<Long> report = new ArrayList<>(mentionIds);
        if ("CLIENT_REPORT".equals(suggestion) && report.isEmpty()) sb.append("No se ha mencionado a ningún cliente: pide que lo mencione con @.\n");
        for (Long id : report) {
            List<WorkoutAssignment> month = assignments.findByClientIdInAndScheduledDateBetween(List.of(id), today.minusDays(29), today);
            List<WorkoutAssignment> week = month.stream().filter(w -> !w.getScheduledDate().isBefore(today.minusDays(6))).toList();
            ClientProfile p = profiles.findById(id).orElse(null);
            String tagNames = p == null ? "" : tags.findAllById(p.getTagIds()).stream().map(ClientTag::getName).collect(Collectors.joining(", "));
            long upcoming = events.findBookedByClient(id, List.of("BOOKED"), DateTimes.startOfDay(today), DateTimes.endExclusive(today.plusDays(7))).size();
            LocalDate l = last.get(id);
            sb.append("- ").append(pseudo.get(id)).append(": cumplimiento semanal ").append(pct(week)).append(", mensual ").append(pct(month))
              .append("; última actividad ").append(l == null ? "ninguna" : "hace " + ChronoUnit.DAYS.between(l, today) + " días")
              .append("; sesiones reservadas en los próximos 7 días: ").append(upcoming)
              .append(tagNames.isBlank() ? "" : "; etiquetas: " + tagNames).append('\n');
        }
        return sb.toString();
    }

    private static String pct(List<WorkoutAssignment> l) {
        if (l.isEmpty()) return "sin workouts asignados";
        return Math.round(l.stream().filter(w -> w.getCompletedDate() != null).count() * 100.0 / l.size()) + " %";
    }

    /**
     * Sustituye los nombres de los clientes propios por su seudónimo: primero los nombres completos (los más largos antes) y
     * después cada parte del nombre de 3 o más letras ("Lucía", "Fernández"). Una parte que comparten varios clientes pasa a
     * "[cliente]" para no revelar a quién se refiere (Nota de Seguridad T83).
     */
    static String hide(String text, List<AppUser> own, Map<Long, String> pseudo) {
        String out = text;
        for (AppUser u : own.stream().sorted(Comparator.comparingInt((AppUser u) -> -u.getFullName().length())).toList()) {
            if (u.getFullName().isBlank()) continue;
            out = out.replaceAll("(?iu)" + Pattern.quote(u.getFullName()), Matcher.quoteReplacement(pseudo.get(u.getId())));
        }
        Map<String, String> parts = new HashMap<>();
        for (AppUser u : own) {
            for (String p : u.getFullName().split("\\s+")) {
                if (p.codePointCount(0, p.length()) < 3) continue;
                String key = p.toLowerCase(Locale.ROOT);
                parts.merge(key, pseudo.get(u.getId()), (a, b) -> a.equals(b) ? a : "[cliente]");
            }
        }
        for (Map.Entry<String, String> e : parts.entrySet().stream().sorted(Comparator.comparingInt(e -> -e.getKey().length())).toList()) {
            out = out.replaceAll("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}])", Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    /** Vuelve a poner los nombres reales en la respuesta. */
    private static String reveal(String text, Map<String, String> realByPseudo) {
        Matcher m = PSEUDO.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(realByPseudo.getOrDefault(m.group(), m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    private static AiConversationDto toDto(AiConversation c) { return new AiConversationDto(c.getId(), c.getTitle(), c.getContext(), c.getUpdatedAt()); }

    private AiMessageDto toDto(AiMessage m) {
        List<AiMentionDto> mentions;
        try { mentions = json.readValue(m.getMentions(), new TypeReference<>() {}); } catch (Exception e) { mentions = List.of(); }
        return new AiMessageDto(m.getId(), m.getRole(), m.getText(), mentions, m.getCreatedAt());
    }
}
