package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Comunidad: grupos de clientes de un entrenador con publicaciones, workouts compartidos (con ranking) y "me gusta".
 * Dueño (o ADMIN): todo. Cliente miembro: ver, dar "me gusta" y publicar si whoCanPost = EVERYONE.
 */
@Service
public class CommunityService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int RANKING_SIZE = 10;

    private final CommunityGroupRepository groups;
    private final CommunityPostRepository posts;
    private final UserRepository users;
    private final WorkoutRepository workouts;
    private final WorkoutAssignmentRepository assignments;
    private final AccessService access;

    public CommunityService(CommunityGroupRepository g, CommunityPostRepository p, UserRepository u, WorkoutRepository w,
                            WorkoutAssignmentRepository a, AccessService ac) {
        groups = g; posts = p; users = u; workouts = w; assignments = a; access = ac;
    }

    @Transactional(readOnly = true)
    public List<GroupDto> list() {
        AuthUser me = access.current();
        List<CommunityGroup> list = switch (me.role()) {
            case ADMIN -> groups.findAllByOrderByCreatedAtDesc();
            case TRAINER -> groups.findByTrainerIdOrderByCreatedAtDesc(me.id());
            case CLIENT -> groups.findByMember(me.id());
            case COLLABORATOR -> throw new ForbiddenException("La comunidad no está disponible para colaboradores");
        };
        return list.stream().map(this::toDto).toList();
    }

    @Transactional
    public GroupDto create(GroupRequest r) {
        CommunityGroup g = new CommunityGroup();
        g.setTrainerId(access.current().id());
        apply(g, r);
        return toDto(groups.save(g));
    }

    @Transactional
    public GroupDto update(Long id, GroupRequest r) {
        CommunityGroup g = owned(id);
        apply(g, r);
        return toDto(g);
    }

    @Transactional
    public void delete(Long id) {
        CommunityGroup g = owned(id);
        posts.deleteAll(posts.findByGroupIdOrderByCreatedAtDesc(id));
        groups.delete(g);
    }

    @Transactional(readOnly = true)
    public List<PostDto> posts(Long groupId) {
        CommunityGroup g = readable(groupId);
        AuthUser me = access.current();
        List<CommunityPost> list = posts.findByGroupIdOrderByCreatedAtDesc(groupId);
        Map<Long, String> names = names(list.stream().map(CommunityPost::getAuthorId).collect(Collectors.toSet()));
        return list.stream().map(p -> toDto(p, g, me, names)).toList();
    }

    @Transactional
    public PostDto createPost(Long groupId, PostRequest r) {
        CommunityGroup g = readable(groupId);
        AuthUser me = access.current();
        boolean owner = isOwner(g, me);
        if (!owner && !"EVERYONE".equals(g.getWhoCanPost())) throw new ForbiddenException("En este grupo solo publica el entrenador");
        if (r.workoutId() != null) {
            if (!owner) throw new ForbiddenException("Solo el entrenador comparte workouts");
            Workout w = workouts.findById(r.workoutId()).orElseThrow(() -> new BadRequestException("El workout no existe"));
            if (!Objects.equals(w.getTrainerId(), g.getTrainerId())) throw new BadRequestException("El workout no es del entrenador del grupo");
        }
        CommunityPost p = new CommunityPost();
        p.setGroupId(groupId);
        p.setAuthorId(me.id());
        p.setBody(r.text().trim());
        p.setWorkoutId(r.workoutId());
        p = posts.save(p);
        return toDto(p, g, me, names(Set.of(me.id())));
    }

    @Transactional
    public void deletePost(Long postId) {
        CommunityPost p = posts.findById(postId).orElseThrow(() -> new NotFoundException("Publicación no encontrada"));
        CommunityGroup g = groups.findById(p.getGroupId()).orElseThrow(() -> new NotFoundException("Grupo no encontrado"));
        AuthUser me = access.current();
        if (!me.id().equals(p.getAuthorId()) && !isOwner(g, me)) throw new ForbiddenException("No puedes borrar esta publicación");
        posts.delete(p);
    }

    /** Alterna el "me gusta" del usuario actual. */
    @Transactional
    public PostDto toggleLike(Long postId) {
        CommunityPost p = posts.findById(postId).orElseThrow(() -> new NotFoundException("Publicación no encontrada"));
        CommunityGroup g = readable(p.getGroupId());
        AuthUser me = access.current();
        if (!p.getLikedBy().remove(me.id())) p.getLikedBy().add(me.id());
        return toDto(p, g, me, names(Set.of(p.getAuthorId())));
    }

    // ---------- internos ----------

    private void apply(CommunityGroup g, GroupRequest r) {
        Set<Long> members = new LinkedHashSet<>(r.memberIds());
        for (Long id : members) access.assertCanAccessClient(id);   // solo clientes propios
        g.setName(r.name().trim());
        g.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        g.setEmoji(r.emoji() == null || r.emoji().isBlank() ? null : r.emoji());
        g.setWhoCanPost(r.whoCanPost());
        g.setShareResults(r.shareResults());
        g.getMemberIds().clear();
        g.getMemberIds().addAll(members);
    }

    private boolean isOwner(CommunityGroup g, AuthUser me) {
        return me.role() == Role.ADMIN || me.id().equals(g.getTrainerId());
    }

    private CommunityGroup owned(Long id) {
        CommunityGroup g = groups.findById(id).orElseThrow(() -> new NotFoundException("Grupo no encontrado"));
        access.assertOwnerOrAdmin(g.getTrainerId());
        return g;
    }

    /** Dueño, ADMIN o cliente miembro. */
    private CommunityGroup readable(Long id) {
        CommunityGroup g = groups.findById(id).orElseThrow(() -> new NotFoundException("Grupo no encontrado"));
        AuthUser me = access.current();
        if (!isOwner(g, me) && !g.getMemberIds().contains(me.id())) throw new ForbiddenException("No eres miembro de este grupo");
        return g;
    }

    private Map<Long, String> names(Collection<Long> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    private GroupDto toDto(CommunityGroup g) {
        List<MemberDto> members = users.findAllById(g.getMemberIds()).stream()
                .map(u -> new MemberDto(u.getId(), u.getFullName()))
                .sorted(Comparator.comparing(MemberDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new GroupDto(g.getId(), g.getName(), g.getDescription(), g.getEmoji(), g.getWhoCanPost(), g.isShareResults(),
                g.getCreatedAt(), members);
    }

    private PostDto toDto(CommunityPost p, CommunityGroup g, AuthUser me, Map<Long, String> names) {
        SharedWorkoutDto shared = p.getWorkoutId() == null ? null
                : workouts.findById(p.getWorkoutId()).map(w -> new SharedWorkoutDto(w.getId(), w.getName(), ranking(w.getId(), g))).orElse(null);
        return new PostDto(p.getId(), p.getGroupId(), p.getAuthorId(), names.getOrDefault(p.getAuthorId(), "—"),
                me.id().equals(p.getAuthorId()), p.getBody(), p.getCreatedAt(), shared,
                p.getLikedBy().size(), p.getLikedBy().contains(me.id()));
    }

    /** Miembros que completaron el workout, por fecha de finalización. Vacío si el grupo no comparte resultados. */
    private List<RankingDto> ranking(Long workoutId, CommunityGroup g) {
        if (!g.isShareResults() || g.getMemberIds().isEmpty()) return List.of();
        List<WorkoutAssignment> done = assignments
                .findByWorkoutIdAndClientIdInAndCompletedDateIsNotNullOrderByCompletedDateAsc(workoutId, g.getMemberIds());
        Map<Long, WorkoutAssignment> first = done.stream()
                .collect(Collectors.toMap(WorkoutAssignment::getClientId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, String> names = names(first.keySet());
        return first.values().stream().limit(RANKING_SIZE)
                .map(a -> new RankingDto(names.getOrDefault(a.getClientId(), "—"), "Completado el " + a.getCompletedDate().format(DAY)))
                .toList();
    }
}
