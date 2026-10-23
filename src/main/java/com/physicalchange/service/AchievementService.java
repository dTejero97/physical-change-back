package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Logros (docs/api-libreria-v2.md): categorías y logros del sistema (solo lectura, con visibilidad y ocultación
 * por profesional) y propios con condición y duración. El desbloqueo por cliente es T58.
 */
@Service
public class AchievementService {
    static final String PERSONAL = "Logros personalizados";

    private final AchievementCategoryRepository categories;
    private final AchievementRepository achievements;
    private final AchievementCategoryPrefRepository prefs;
    private final AchievementHiddenRepository hidden;
    private final ExerciseRepository exercises;
    private final FileService files;
    private final TagRefs tags;
    private final AccessService access;

    public AchievementService(AchievementCategoryRepository c, AchievementRepository a, AchievementCategoryPrefRepository p,
                              AchievementHiddenRepository h, ExerciseRepository e, FileService f, TagRefs t, AccessService ac) {
        categories = c; achievements = a; prefs = p; hidden = h; exercises = e; files = f; tags = t; access = ac;
    }

    /** Categorías visibles para el profesional con sus logros. La categoría propia se crea oculta la primera vez. */
    @Transactional
    public List<CategoryDto> list() {
        Long me = access.current().id();
        if (categories.countByTrainerId(me) == 0) {
            AchievementCategory c = new AchievementCategory();
            c.setTrainerId(me);
            c.setName(PERSONAL);
            c.setDescription("Logros creados por ti para tus clientes.");
            c.setVisibility("HIDDEN");
            categories.save(c);
        }
        List<AchievementCategory> list = categories.findVisibleTo(me);
        Map<Long, String> prefByCat = prefs.findByTrainerId(me).stream()
                .collect(Collectors.toMap(AchievementCategoryPref::getCategoryId, AchievementCategoryPref::getVisibility));
        Set<Long> hiddenIds = hidden.findByTrainerId(me).stream().map(AchievementHidden::getAchievementId).collect(Collectors.toSet());
        List<Achievement> all = achievements.findByCategoryIdInOrderByPositionAscIdAsc(list.stream().map(AchievementCategory::getId).toList())
                .stream().filter(a -> a.isSystem() || a.getTrainerId().equals(me)).toList();
        Map<Long, String> exerciseNames = exerciseNames(all);
        Map<Long, List<AchievementDto>> byCat = all.stream().collect(Collectors.groupingBy(Achievement::getCategoryId,
                Collectors.mapping(a -> toDto(a, hiddenIds.contains(a.getId()), exerciseNames), Collectors.toList())));
        return list.stream().map(c -> toDto(c, prefByCat.get(c.getId()), byCat.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional
    public CategoryDto createCategory(CategoryRequest r) {
        AchievementCategory c = new AchievementCategory();
        c.setTrainerId(access.current().id());
        applyCategory(c, r);
        return toDto(categories.save(c), null, List.of());
    }

    /** En las del sistema solo cambia la visibilidad (ALL u HIDDEN) para este profesional. */
    @Transactional
    public CategoryDto updateCategory(Long id, CategoryRequest r) {
        AchievementCategory c = categories.findById(id).orElseThrow(() -> new NotFoundException("Categoría no encontrada"));
        Long me = access.current().id();
        if (c.isSystem()) {
            if ("TAGS".equals(r.visibility())) throw new BadRequestException("Las categorías del sistema solo pueden ser visibles u ocultas");
            AchievementCategoryPref p = prefs.findById(new AchievementCategoryPref.Key(me, id)).orElse(new AchievementCategoryPref(me, id, r.visibility()));
            p.setVisibility(r.visibility());
            prefs.save(p);
            return toDto(c, r.visibility(), categoryAchievements(c, me));
        }
        access.assertOwnerOrAdmin(c.getTrainerId());
        applyCategory(c, r);
        return toDto(c, null, categoryAchievements(c, me));
    }

    /** Solo propias; borra sus logros y sus insignias subidas. */
    @Transactional
    public void deleteCategory(Long id) {
        AchievementCategory c = ownCategory(id);
        for (Achievement a : achievements.findByCategoryId(id)) removeAchievement(a);
        categories.delete(c);
    }

    @Transactional
    public AchievementDto create(AchievementRequest r) {
        Achievement a = new Achievement();
        a.setTrainerId(access.current().id());
        applyAchievement(a, r);
        a = achievements.save(a);
        return toDto(a, false, exerciseNames(List.of(a)));
    }

    @Transactional
    public AchievementDto update(Long id, AchievementRequest r) {
        Achievement a = ownAchievement(id);
        Long oldBadge = a.getBadgeFileId();
        applyAchievement(a, r);
        achievements.flush();
        if (oldBadge != null && !oldBadge.equals(a.getBadgeFileId())) files.removeById(oldBadge);
        return toDto(a, isHidden(a), exerciseNames(List.of(a)));
    }

    @Transactional
    public void delete(Long id) { removeAchievement(ownAchievement(id)); }

    /** Ocultar o mostrar un logro (también los del sistema) a los clientes de este profesional. */
    @Transactional
    public AchievementDto setHidden(Long id, HiddenRequest r) {
        Achievement a = achievements.findById(id).orElseThrow(() -> new NotFoundException("Logro no encontrado"));
        Long me = access.current().id();
        if (!a.isSystem()) access.assertOwnerOrAdmin(a.getTrainerId());
        AchievementHidden.Key key = new AchievementHidden.Key(me, id);
        if (r.hidden()) { if (!hidden.existsById(key)) hidden.save(new AchievementHidden(me, id)); }
        else hidden.deleteById(key);
        return toDto(a, r.hidden(), exerciseNames(List.of(a)));
    }

    // ---------- internos ----------

    private AchievementCategory ownCategory(Long id) {
        AchievementCategory c = categories.findById(id).orElseThrow(() -> new NotFoundException("Categoría no encontrada"));
        if (c.isSystem()) throw new ForbiddenException("Las categorías del sistema no se pueden modificar");
        access.assertOwnerOrAdmin(c.getTrainerId());
        return c;
    }

    private Achievement ownAchievement(Long id) {
        Achievement a = achievements.findById(id).orElseThrow(() -> new NotFoundException("Logro no encontrado"));
        if (a.isSystem()) throw new ForbiddenException("Los logros del sistema no se pueden modificar");
        access.assertOwnerOrAdmin(a.getTrainerId());
        return a;
    }

    private void removeAchievement(Achievement a) {
        Long badge = a.getBadgeFileId();
        hidden.findByTrainerId(a.getTrainerId()).stream().filter(h -> h.getAchievementId().equals(a.getId())).forEach(hidden::delete);
        achievements.delete(a);
        achievements.flush();
        files.removeById(badge);
    }

    private void applyCategory(AchievementCategory c, CategoryRequest r) {
        c.setName(r.name().trim());
        c.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        c.setVisibility(r.visibility());
        c.getTagIds().clear();
        if ("TAGS".equals(r.visibility())) c.getTagIds().addAll(tags.own(r.tagIds(), c.getTrainerId(), true));
    }

    /** Valida la condición y la duración según su tipo y limpia los campos que no aplican. */
    private void applyAchievement(Achievement a, AchievementRequest r) {
        Long me = a.getTrainerId();
        AchievementCategory c = categories.findById(r.categoryId()).filter(x -> me.equals(x.getTrainerId()))
                .orElseThrow(() -> new BadRequestException("Elige una categoría propia"));
        a.setCategoryId(c.getId());
        a.setName(r.name().trim());
        a.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        a.setBadge("custom");
        a.setTier("TEAL");
        if (r.badgeFileId() != null) files.owned(r.badgeFileId(), "IMAGE");
        a.setBadgeFileId(r.badgeFileId());

        String type = r.conditionType();
        a.setConditionType(type);
        a.setExerciseId(null); a.setWeightKg(null); a.setReps(null); a.setDays(null); a.setTargetKg(null); a.setMonths(null);
        switch (type) {
            case "WEIGHT_EXERCISE" -> { a.setExerciseId(exercise(r.exerciseId(), me)); a.setWeightKg(require(r.weightKg(), "el peso objetivo")); }
            case "TOTAL_REPS" -> { a.setExerciseId(exercise(r.exerciseId(), me)); a.setReps(require(r.reps(), "las repeticiones")); }
            case "STREAK" -> a.setDays(require(r.days(), "los días de racha"));
            case "BODY_WEIGHT" -> a.setTargetKg(require(r.targetKg(), "el peso corporal objetivo"));
            case "TIME_WITH_YOU" -> a.setMonths(require(r.months(), "los meses"));
            default -> throw new BadRequestException("Condición no válida");
        }

        a.setDurationType(r.durationType());
        a.setStartDate(null); a.setEndDate(null); a.setDaysAfter(null);
        if ("DATE_RANGE".equals(r.durationType())) {
            if (r.startDate() == null || r.endDate() == null || r.endDate().isBefore(r.startDate())) throw new BadRequestException("Indica un rango de fechas válido");
            a.setStartDate(r.startDate()); a.setEndDate(r.endDate());
        } else if ("AFTER_ASSIGNMENT".equals(r.durationType())) {
            a.setDaysAfter(require(r.daysAfter(), "los días desde la asignación"));
        }
    }

    private Long exercise(Long id, Long trainerId) {
        if (id == null) throw new BadRequestException("Elige el ejercicio");
        Exercise e = exercises.findById(id).orElseThrow(() -> new BadRequestException("Ejercicio no válido"));
        if (e.getOwnerId() != null && !e.getOwnerId().equals(trainerId)) throw new BadRequestException("Ejercicio no válido");
        return e.getId();
    }

    private static <T> T require(T v, String what) {
        if (v == null) throw new BadRequestException("Indica " + what);
        return v;
    }

    private boolean isHidden(Achievement a) { return hidden.existsById(new AchievementHidden.Key(access.current().id(), a.getId())); }

    private List<AchievementDto> categoryAchievements(AchievementCategory c, Long me) {
        Set<Long> hiddenIds = hidden.findByTrainerId(me).stream().map(AchievementHidden::getAchievementId).collect(Collectors.toSet());
        List<Achievement> list = achievements.findByCategoryIdInOrderByPositionAscIdAsc(List.of(c.getId()));
        Map<Long, String> names = exerciseNames(list);
        return list.stream().map(a -> toDto(a, hiddenIds.contains(a.getId()), names)).toList();
    }

    private Map<Long, String> exerciseNames(List<Achievement> list) {
        Set<Long> ids = list.stream().map(Achievement::getExerciseId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : exercises.findAllById(ids).stream().collect(Collectors.toMap(Exercise::getId, Exercise::getName, (x, y) -> x));
    }

    private CategoryDto toDto(AchievementCategory c, String pref, List<AchievementDto> list) {
        String visibility = c.isSystem() && pref != null ? pref : c.getVisibility();
        return new CategoryDto(c.getId(), c.getName(), c.getDescription(), c.getIcon(), c.isSystem(), visibility,
                new ArrayList<>(c.getTagIds()), c.getCreatedAt(), list);
    }

    private AchievementDto toDto(Achievement a, boolean isHidden, Map<Long, String> exerciseNames) {
        return new AchievementDto(a.getId(), a.getCategoryId(), a.getName(), a.getDescription(), a.getGroupLabel(), a.getBadge(),
                a.getBadgeFileId(), files.url(a.getBadgeFileId()), a.getTier(), a.getConditionType(), a.getExerciseId(),
                a.getExerciseId() == null ? null : exerciseNames.get(a.getExerciseId()), a.getWeightKg(), a.getReps(), a.getDays(),
                a.getTargetKg(), a.getMonths(), a.getDurationType(), a.getStartDate(), a.getEndDate(), a.getDaysAfter(),
                a.isSystem(), isHidden, a.getCreatedAt());
    }
}
