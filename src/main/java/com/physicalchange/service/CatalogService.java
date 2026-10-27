package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Productos de pago único y planes de suscripción (ADR-004 §3, docs/api-negocio.md). Mismas reglas para los dos:
 * portada, visibilidad HIDDEN (ALL → 409 hasta la tienda en la app), hasta 5 bonos de tipos de sesión propios, etiquetas
 * automáticas propias y archivado lógico si ya hay ventas. Lo ajeno responde 404.
 */
@Service
public class CatalogService {
    private static final Set<Integer> TAX_RATES = Set.of(0, 4, 10, 21);

    private final ProductRepository products;
    private final SubscriptionPlanRepository plans;
    private final BonusDefinitionRepository bonuses;
    private final ReceiptLineRepository lines;
    private final ClientSubscriptionRepository subscriptions;
    private final SessionTypeRepository sessionTypes;
    private final ClientTagRepository tags;
    private final FileService files;
    private final AccessService access;

    public CatalogService(ProductRepository p, SubscriptionPlanRepository pl, BonusDefinitionRepository b, ReceiptLineRepository l,
                          ClientSubscriptionRepository s, SessionTypeRepository st, ClientTagRepository t, FileService f, AccessService a) {
        products = p; plans = pl; bonuses = b; lines = l; subscriptions = s; sessionTypes = st; tags = t; files = f; access = a;
    }

    // ---------- Productos ----------

    @Transactional(readOnly = true)
    public Page<ProductDto> listProducts(String q, boolean archived, Pageable p) {
        List<Product> all = filter(products.findByTrainerIdAndArchived(me().id(), archived), q, Product::getName, Product::getDescription, Product::getPrice);
        all.sort(order(p.getSort(), Product::getName, Product::getPrice, Product::getCreatedAt));
        return page(productDtos(slice(all, p)), p, all.size());
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(Long id) { return productDtos(List.of(ownedProduct(id))).get(0); }

    @Transactional
    public ProductDto createProduct(ProductRequest r) {
        Product x = new Product();
        x.setTrainerId(me().id());
        applyProduct(x, r);
        x = products.save(x);
        saveBonuses(r.bonuses(), x.getId(), null);
        return productDtos(List.of(x)).get(0);
    }

    @Transactional
    public ProductDto updateProduct(Long id, ProductRequest r) {
        Product x = ownedProduct(id);
        applyProduct(x, r);
        x.setUpdatedAt(LocalDateTime.now());
        bonuses.deleteByProductId(id);
        bonuses.flush();
        saveBonuses(r.bonuses(), id, null);
        return productDtos(List.of(x)).get(0);
    }

    /** 204 (null) si se borra; el producto archivado si ya tiene ventas. */
    @Transactional
    public ProductDto deleteProduct(Long id) {
        Product x = ownedProduct(id);
        if (lines.existsByProductId(id)) {
            x.setArchived(true);
            x.setUpdatedAt(LocalDateTime.now());
            return productDtos(List.of(x)).get(0);
        }
        bonuses.deleteByProductId(id);
        products.delete(x);
        return null;
    }

    private void applyProduct(Product x, ProductRequest r) {
        x.setName(r.name().strip());
        x.setDescription(r.description().strip());
        x.setPrice(r.price().setScale(2, RoundingMode.HALF_UP));
        x.setTaxRate(taxRate(r.taxRate()));
        x.setVisibility(visibility(r.visibility()));
        x.setCover(files.checkCover(r.cover(), true));
        x.getAutoTagIds().clear();
        x.getAutoTagIds().addAll(checkTags(r.autoTagIds()));
        checkBonuses(r.bonuses());
    }

    Product ownedProduct(Long id) {
        return products.findById(id).filter(x -> x.getTrainerId().equals(me().id())).orElseThrow(() -> new NotFoundException("Producto no encontrado"));
    }

    private List<ProductDto> productDtos(List<Product> list) {
        Set<Long> ids = list.stream().map(Product::getId).collect(Collectors.toSet());
        Map<Long, List<BonusDefinitionDto>> b = bonusDtos(ids.isEmpty() ? List.of() : bonuses.findByProductIdIn(ids), BonusDefinition::getProductId);
        Map<Long, Long> sales = counts(ids.isEmpty() ? List.of() : lines.salesByProduct(ids));
        return list.stream().map(x -> {
            List<BonusDefinitionDto> bs = b.getOrDefault(x.getId(), List.of());
            return new ProductDto(x.getId(), x.getName(), x.getDescription(), x.getPrice(), x.getTaxRate(), x.getVisibility(), x.getCover(),
                    files.coverUrl(x.getCover()), bs, sorted(x.getAutoTagIds()), !x.getAutoTagIds().isEmpty(), !bs.isEmpty(),
                    sales.getOrDefault(x.getId(), 0L), x.isArchived(), x.getCreatedAt());
        }).toList();
    }

    // ---------- Planes ----------

    @Transactional(readOnly = true)
    public Page<SubscriptionPlanDto> listPlans(String q, boolean archived, Pageable p) {
        List<SubscriptionPlan> all = filter(plans.findByTrainerIdAndArchived(me().id(), archived), q,
                SubscriptionPlan::getName, SubscriptionPlan::getDescription, SubscriptionPlan::getPrice);
        all.sort(order(p.getSort(), SubscriptionPlan::getName, SubscriptionPlan::getPrice, SubscriptionPlan::getCreatedAt));
        return page(planDtos(slice(all, p)), p, all.size());
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanDto getPlan(Long id) { return planDtos(List.of(ownedPlan(id))).get(0); }

    @Transactional
    public SubscriptionPlanDto createPlan(SubscriptionPlanRequest r) {
        SubscriptionPlan x = new SubscriptionPlan();
        x.setTrainerId(me().id());
        applyPlan(x, r);
        x = plans.save(x);
        saveBonuses(r.bonuses(), null, x.getId());
        return planDtos(List.of(x)).get(0);
    }

    @Transactional
    public SubscriptionPlanDto updatePlan(Long id, SubscriptionPlanRequest r) {
        SubscriptionPlan x = ownedPlan(id);
        applyPlan(x, r);
        x.setUpdatedAt(LocalDateTime.now());
        bonuses.deleteByPlanId(id);
        bonuses.flush();
        saveBonuses(r.bonuses(), null, id);
        return planDtos(List.of(x)).get(0);
    }

    @Transactional
    public SubscriptionPlanDto deletePlan(Long id) {
        SubscriptionPlan x = ownedPlan(id);
        if (lines.existsByPlanId(id) || subscriptions.existsByPlanId(id)) {
            x.setArchived(true);
            x.setUpdatedAt(LocalDateTime.now());
            return planDtos(List.of(x)).get(0);
        }
        bonuses.deleteByPlanId(id);
        plans.delete(x);
        return null;
    }

    private void applyPlan(SubscriptionPlan x, SubscriptionPlanRequest r) {
        x.setName(r.name().strip());
        x.setDescription(r.description().strip());
        x.setPrice(r.price().setScale(2, RoundingMode.HALF_UP));
        x.setTaxRate(taxRate(r.taxRate()));
        x.setFrequency(r.frequency());
        x.setVisibility(visibility(r.visibility()));
        x.setCover(files.checkCover(r.cover(), true));
        x.getAutoTagIds().clear();
        x.getAutoTagIds().addAll(checkTags(r.autoTagIds()));
        checkBonuses(r.bonuses());
    }

    SubscriptionPlan ownedPlan(Long id) {
        return plans.findById(id).filter(x -> x.getTrainerId().equals(me().id())).orElseThrow(() -> new NotFoundException("Plan no encontrado"));
    }

    private List<SubscriptionPlanDto> planDtos(List<SubscriptionPlan> list) {
        Set<Long> ids = list.stream().map(SubscriptionPlan::getId).collect(Collectors.toSet());
        Map<Long, List<BonusDefinitionDto>> b = bonusDtos(ids.isEmpty() ? List.of() : bonuses.findByPlanIdIn(ids), BonusDefinition::getPlanId);
        Map<Long, Long> sales = counts(ids.isEmpty() ? List.of() : lines.salesByPlan(ids));
        Map<Long, Long> active = counts(ids.isEmpty() ? List.of() : subscriptions.countActiveByPlan(ids));
        return list.stream().map(x -> {
            List<BonusDefinitionDto> bs = b.getOrDefault(x.getId(), List.of());
            return new SubscriptionPlanDto(x.getId(), x.getName(), x.getDescription(), x.getPrice(), x.getTaxRate(), x.getVisibility(), x.getCover(),
                    files.coverUrl(x.getCover()), bs, sorted(x.getAutoTagIds()), !x.getAutoTagIds().isEmpty(), !bs.isEmpty(),
                    sales.getOrDefault(x.getId(), 0L), x.isArchived(), x.getCreatedAt(), x.getFrequency(), active.getOrDefault(x.getId(), 0L));
        }).toList();
    }

    // ---------- Comunes ----------

    private AuthUser me() { return access.current(); }

    private static short taxRate(Integer t) {
        if (t == null || !TAX_RATES.contains(t)) throw new BadRequestException("Impuesto no válido (21, 10, 4 o 0 %)");
        return t.shortValue();
    }

    private static String visibility(String v) {
        if ("ALL".equals(v)) throw new ConflictException("Disponible con la tienda de la app del cliente");
        return "HIDDEN";
    }

    private List<Long> checkTags(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        AuthUser me = me();
        for (Long id : new LinkedHashSet<>(ids)) {
            ClientTag t = tags.findById(id).orElseThrow(() -> new BadRequestException("La etiqueta no existe"));
            if (me.role() != Role.ADMIN && !t.getTrainerId().equals(me.id())) throw new BadRequestException("La etiqueta no es tuya");
        }
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private void checkBonuses(List<BonusDefinitionRequest> list) {
        if (list == null) return;
        if (list.size() > 5) throw new BadRequestException("Como máximo 5 bonos");
        for (BonusDefinitionRequest b : list) {
            sessionTypes.findById(b.sessionTypeId()).filter(s -> s.getTrainerId().equals(me().id()))
                    .orElseThrow(() -> new BadRequestException("El tipo de sesión no existe"));
        }
    }

    private void saveBonuses(List<BonusDefinitionRequest> list, Long productId, Long planId) {
        if (list == null) return;
        for (BonusDefinitionRequest r : list) {
            BonusDefinition b = new BonusDefinition();
            b.setProductId(productId);
            b.setPlanId(planId);
            b.setSessionTypeId(r.sessionTypeId());
            b.setSessions(r.sessions().shortValue());
            b.setValidDays(r.validDays().shortValue());
            bonuses.save(b);
        }
    }

    private Map<Long, List<BonusDefinitionDto>> bonusDtos(List<BonusDefinition> list, Function<BonusDefinition, Long> owner) {
        Map<Long, SessionType> types = sessionTypes.findAllById(list.stream().map(BonusDefinition::getSessionTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SessionType::getId, t -> t));
        Map<Long, List<BonusDefinitionDto>> out = new HashMap<>();
        for (BonusDefinition b : list.stream().sorted(Comparator.comparing(BonusDefinition::getId)).toList()) {
            SessionType t = types.get(b.getSessionTypeId());
            out.computeIfAbsent(owner.apply(b), k -> new ArrayList<>()).add(new BonusDefinitionDto(b.getId(), b.getSessionTypeId(),
                    t == null ? "—" : t.getName(), t == null ? null : t.getColor(), b.getSessions(), b.getValidDays()));
        }
        return out;
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put((Long) r[0], ((Number) r[1]).longValue());
        return m;
    }

    private static List<Long> sorted(Set<Long> ids) { return ids.stream().sorted().toList(); }

    private static <T> List<T> filter(List<T> list, String q, Function<T, String> name, Function<T, String> desc, Function<T, BigDecimal> price) {
        String n = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return list.stream().filter(x -> n.isEmpty() || name.apply(x).toLowerCase(Locale.ROOT).contains(n)
                || desc.apply(x).toLowerCase(Locale.ROOT).contains(n) || price.apply(x).toPlainString().contains(n.replace(',', '.')))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static <T> Comparator<T> order(Sort sort, Function<T, String> name, Function<T, BigDecimal> price, Function<T, LocalDateTime> created) {
        Sort.Order o = sort.stream().findFirst().orElse(Sort.Order.desc("createdAt"));
        Comparator<T> c = switch (o.getProperty()) {
            case "name" -> Comparator.comparing(x -> name.apply(x).toLowerCase(Locale.ROOT));
            case "price" -> Comparator.comparing(price);
            default -> Comparator.comparing(created);
        };
        return o.isDescending() ? c.reversed() : c;
    }

    private static <T> List<T> slice(List<T> all, Pageable p) {
        int a = (int) Math.min(p.getOffset(), all.size());
        return all.subList(a, Math.min(a + p.getPageSize(), all.size()));
    }

    private static <T> Page<T> page(List<T> content, Pageable p, long total) { return new PageImpl<>(content, p, total); }
}
