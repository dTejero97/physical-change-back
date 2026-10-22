package com.physicalchange.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ficha del cliente (sección Clientes): datos de gestión, etiquetas y métricas calculadas
 * (cumplimiento 7/30 días, última actividad, último pago). Contrato: docs/api-clientes.md.
 */
@Service
public class ClientProfileService {
    private final ClientProfileRepository profiles;
    private final UserRepository users;
    private final ClientTagRepository tags;
    private final WorkoutAssignmentRepository assignments;
    private final ExerciseLogRepository logs;
    private final PaymentRepository payments;
    private final ChatMessageRepository messages;
    private final AccessService access;
    private final ObjectMapper json;

    public ClientProfileService(ClientProfileRepository p, UserRepository u, ClientTagRepository t, WorkoutAssignmentRepository a,
                                ExerciseLogRepository l, PaymentRepository pay, ChatMessageRepository m, AccessService ac, ObjectMapper j) {
        profiles = p; users = u; tags = t; assignments = a; logs = l; payments = pay; messages = m; access = ac; json = j;
    }

    /** Devuelve la ficha del cliente y la crea si aún no existe. */
    @Transactional
    public ClientProfile ensure(Long clientId) {
        return profiles.findById(clientId).orElseGet(() -> profiles.save(new ClientProfile(clientId)));
    }

    /** Fichas de los clientes del entrenador (o de todos si es ADMIN), activos y archivados, sin los eliminados. */
    @Transactional
    public List<ClientProfileDto> list() {
        AuthUser me = access.current();
        List<AppUser> clients = me.role() == Role.ADMIN ? users.findByRole(Role.CLIENT) : users.findByRoleAndTrainerId(Role.CLIENT, me.id());
        if (clients.isEmpty()) return List.of();
        List<Long> ids = clients.stream().map(AppUser::getId).toList();

        Map<Long, ClientProfile> byId = profiles.findByClientIdIn(ids).stream()
                .collect(Collectors.toMap(ClientProfile::getClientId, Function.identity()));
        for (Long id : ids) if (!byId.containsKey(id)) byId.put(id, profiles.save(new ClientProfile(id)));

        Stats stats = stats(ids);
        return clients.stream()
                .filter(c -> byId.get(c.getId()).getRemovedAt() == null)
                .map(c -> toDto(c, byId.get(c.getId()), stats))
                .toList();
    }

    @Transactional
    public ClientProfileDto update(Long clientId, ClientProfileRequest r) {
        AppUser client = client(clientId);
        ClientProfile p = ensure(clientId);
        p.setPlanName(blankToNull(r.planName()));
        p.setPlanStatus(r.planStatus());
        p.setAssignee(r.assignee());
        p.setAppAccess(r.appAccess());
        p.setHeightCm(r.heightCm());
        p.setWeightKg(r.weightKg());
        p.setGender(r.gender());
        p.setBirthDate(r.birthDate());
        p.setServices(String.join(",", new LinkedHashSet<>(r.services())));
        p.getTagIds().clear();
        p.getTagIds().addAll(ownTags(client.getTrainerId(), r.tagIds()));
        return toDto(client, p, stats(List.of(clientId)));
    }

    /** Borrado lógico desde "Archivados": solo para clientes ya archivados (sin acceso). */
    @Transactional
    public void remove(Long clientId) {
        AppUser client = client(clientId);
        if (client.isActive()) throw new ConflictException("Archiva el cliente antes de eliminarlo");
        ensure(clientId).setRemovedAt(LocalDateTime.now());
    }

    /**
     * Al dar de alta un cliente (manual o por invitación): crea su ficha, le asigna las etiquetas
     * del entrenador y ejecuta las automatizaciones que ya existen en el backend (mensaje de chat).
     */
    @Transactional
    public void onClientCreated(Long clientId, Long trainerId, Collection<Long> tagIds, String planName) {
        ClientProfile p = ensure(clientId);
        if (planName != null && !planName.isBlank()) { p.setPlanName(planName); p.setPlanStatus("PENDING_START"); }
        Set<Long> own = ownTags(trainerId, tagIds == null ? List.of() : tagIds);
        p.getTagIds().addAll(own);
        if (trainerId == null) return;
        for (ClientTag tag : tags.findAllById(own)) {
            TagAutomations a = automationsOf(tag);
            if (a.chat() && a.chatMessage() != null && !a.chatMessage().isBlank()) {
                ChatMessage m = new ChatMessage();
                m.setSenderId(trainerId); m.setRecipientId(clientId); m.setBody(a.chatMessage().trim());
                messages.save(m);
            }
            if (a.service() && a.services() != null && !a.services().isEmpty()) p.setServices(String.join(",", a.services()));
        }
    }

    TagAutomations automationsOf(ClientTag tag) {
        try { return json.readValue(tag.getAutomations(), TagAutomations.class); }
        catch (Exception e) { return TagAutomations.none(); }
    }

    // ---------- internos ----------

    private AppUser client(Long clientId) {
        access.assertCanAccessClient(clientId);
        AppUser c = users.findById(clientId).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        if (c.getRole() != Role.CLIENT) throw new NotFoundException("Cliente no encontrado");
        return c;
    }

    /**
     * Solo se aceptan etiquetas del entrenador del cliente; las demás provocan 400.
     * Cliente sin entrenador (creado por ADMIN): basta con que la etiqueta exista.
     */
    private Set<Long> ownTags(Long trainerId, Collection<Long> tagIds) {
        Set<Long> wanted = new LinkedHashSet<>(tagIds);
        if (wanted.isEmpty()) return wanted;
        List<ClientTag> found = tags.findAllById(wanted);
        boolean ok = found.size() == wanted.size()
                && (trainerId == null || found.stream().allMatch(t -> Objects.equals(t.getTrainerId(), trainerId)));
        if (!ok) throw new BadRequestException("Alguna etiqueta no existe o no es tuya");
        return wanted;
    }

    private record Stats(Map<Long, int[]> compliance, Map<Long, LocalDate> lastActivity, Map<Long, Payment> lastPayment) {}

    private Stats stats(List<Long> ids) {
        LocalDate today = LocalDate.now();
        LocalDate weekFrom = today.minusDays(6), monthFrom = today.minusDays(29);

        // [completadas semana, total semana, completadas mes, total mes]
        Map<Long, int[]> counts = new HashMap<>();
        for (WorkoutAssignment a : assignments.findByClientIdInAndScheduledDateBetween(ids, monthFrom, today)) {
            int[] c = counts.computeIfAbsent(a.getClientId(), k -> new int[4]);
            boolean done = a.getCompletedDate() != null;
            c[3]++; if (done) c[2]++;
            if (!a.getScheduledDate().isBefore(weekFrom)) { c[1]++; if (done) c[0]++; }
        }
        Map<Long, int[]> compliance = new HashMap<>();
        counts.forEach((id, c) -> compliance.put(id, new int[]{ pct(c[0], c[1]), pct(c[2], c[3]) }));

        Map<Long, LocalDate> last = new HashMap<>();
        for (Object[] row : logs.lastLogDates(ids)) last.merge((Long) row[0], (LocalDate) row[1], ClientProfileService::max);
        for (Object[] row : assignments.lastCompletedDates(ids)) last.merge((Long) row[0], (LocalDate) row[1], ClientProfileService::max);

        Map<Long, Payment> lastPayment = new HashMap<>();
        for (Payment p : payments.findByClientIdInOrderByDueDateDesc(ids)) lastPayment.putIfAbsent(p.getClientId(), p);

        return new Stats(compliance, last, lastPayment);
    }

    private ClientProfileDto toDto(AppUser c, ClientProfile p, Stats s) {
        int[] comp = s.compliance().getOrDefault(c.getId(), new int[]{ 0, 0 });
        Payment pay = s.lastPayment().get(c.getId());
        String payState = null; LocalDate payDate = null;
        if (pay != null) {
            boolean paid = "PAID".equals(pay.getStatus());
            payState = paid ? "DONE" : pay.getDueDate().isBefore(LocalDate.now()) ? "EXPIRED" : "NOT_COMPLETED";
            payDate = paid ? pay.getPaidDate() : pay.getDueDate();
        }
        List<String> services = p.getServices().isBlank() ? List.of() : List.of(p.getServices().split(","));
        return new ClientProfileDto(c.getId(), c.getFullName(), c.getEmail(), c.isActive(), new ArrayList<>(p.getTagIds()),
                p.getPlanName(), p.getPlanStatus(), payState, payDate, comp[0], comp[1], p.getActivatedAt(), p.getAssignee(),
                s.lastActivity().get(c.getId()), p.isAppAccess(), p.getHeightCm(), p.getWeightKg(), p.getGender(),
                p.getBirthDate(), services);
    }

    private static int pct(int done, int total) { return total == 0 ? 0 : Math.round(done * 100f / total); }
    private static LocalDate max(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
