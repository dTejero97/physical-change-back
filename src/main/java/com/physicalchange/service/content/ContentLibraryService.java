package com.physicalchange.service.content;

import com.physicalchange.domain.*;
import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.exception.*;
import com.physicalchange.repository.*;
import com.physicalchange.service.social.SocialNetwork;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Ideas, ganchos, copys y colecciones (docs/api-contenido.md §2-§4). Los elementos del banco (owner null) se leen y se copian;
 * nunca se editan. Un id de otro espacio responde 404.
 */
@Service
public class ContentLibraryService {
    public static final String HOOK = "HOOK", COPY = "COPY";

    private final ContentIdeaRepository ideas;
    private final ContentSnippetRepository snippets;
    private final ContentCollectionRepository collections;
    private final ContentCollectionItemRepository items;
    private final ContentPostRepository posts;
    private final ContentSupport support;

    public ContentLibraryService(ContentIdeaRepository i, ContentSnippetRepository s, ContentCollectionRepository c,
                                 ContentCollectionItemRepository it, ContentPostRepository p, ContentSupport cs) {
        ideas = i; snippets = s; collections = c; items = it; posts = p; support = cs;
    }

    @Transactional(readOnly = true)
    public CatalogDto catalog() {
        support.workspace();
        return new CatalogDto(Arrays.stream(SocialNetwork.values()).map(n -> new NetworkLabelDto(n.name(), n.label(), null)).toList(),
                ContentSupport.labels(ContentSupport.FORMATS), ContentSupport.labels(ContentSupport.OBJECTIVES), ideas.bankThemes(),
                List.of(ContentSupport.ZONE.getId()));
    }

    // ---------- Ideas ----------

    @Transactional(readOnly = true)
    public Page<ContentIdeaDto> listIdeas(String scope, String format, String objective, String theme, String tag, String q,
                                          Boolean favorite, Boolean used, Pageable page) {
        Long owner = support.workspace();
        boolean bank = bankScope(scope);
        return ideas.search(bank, owner, blank(format), blank(objective), blank(theme), ContentSupport.tagLike(tag),
                ContentSupport.like(cutQ(q)), favorite, used, sorted(page)).map(ContentSupport::idea);
    }

    @Transactional(readOnly = true)
    public ContentIdeaDto getIdea(Long id) { return ContentSupport.idea(visibleIdea(id, support.workspace())); }

    @Transactional
    public ContentIdeaDto createIdea(IdeaRequest r) {
        ContentIdea i = new ContentIdea();
        i.setOwnerId(support.workspace());
        i.setOrigin("OWN");
        apply(i, r);
        return ContentSupport.idea(ideas.save(i));
    }

    @Transactional
    public ContentIdeaDto updateIdea(Long id, IdeaRequest r) {
        ContentIdea i = editableIdea(id);
        apply(i, r);
        return ContentSupport.idea(i);
    }

    @Transactional
    public ContentIdeaDto patchIdea(Long id, IdeaPatchRequest r) {
        ContentIdea i = editableIdea(id);
        if (r.favorite() != null) i.setFavorite(r.favorite());
        if (r.used() != null) i.setUsed(r.used());
        return ContentSupport.idea(i);
    }

    @Transactional
    public void deleteIdea(Long id) {
        ContentIdea i = editableIdea(id);
        posts.clearIdea(i.getId());
        items.deleteByItem("IDEA", i.getId());
        ideas.delete(i);
    }

    /** Copia propia de una idea del banco; si ya existe, la devuelve (200). */
    @Transactional
    public ResponseEntity<ContentIdeaDto> copyIdea(Long id) {
        Long owner = support.workspace();
        ContentIdea src = visibleIdea(id, owner);
        if (!src.isBank()) throw new ConflictException("Esta idea ya es tuya");
        Optional<ContentIdea> existing = ideas.findByOwnerIdAndSourceId(owner, src.getId());
        if (existing.isPresent()) return ResponseEntity.ok(ContentSupport.idea(existing.get()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ContentSupport.idea(copyOf(src, owner)));
    }

    /** Idea propia lista para usar: si es del banco, su copia (la crea si no existe). */
    @Transactional
    public ContentIdea ownIdeaFor(Long id) {
        Long owner = support.workspace();
        ContentIdea src = visibleIdea(id, owner);
        if (!src.isBank()) return src;
        return ideas.findByOwnerIdAndSourceId(owner, src.getId()).orElseGet(() -> copyOf(src, owner));
    }

    /** Idea propia o del banco (404 si es de otro espacio o no existe). */
    public ContentIdea visibleIdea(Long id, Long owner) {
        return ideas.findById(id).filter(i -> i.isBank() || owner.equals(i.getOwnerId()))
                .orElseThrow(() -> new NotFoundException("Idea no encontrada"));
    }

    private ContentIdea editableIdea(Long id) {
        ContentIdea i = visibleIdea(id, support.workspace());
        if (i.isBank()) throw new ConflictException("Las ideas del banco no se editan: cópiala antes");
        return i;
    }

    private ContentIdea copyOf(ContentIdea src, Long owner) {
        ContentIdea c = new ContentIdea();
        c.setOwnerId(owner);
        c.setOrigin("BANK");
        c.setSourceId(src.getId());
        c.setTitle(src.getTitle()); c.setHook(src.getHook()); c.setScript(src.getScript()); c.setFormat(src.getFormat());
        c.setObjective(src.getObjective()); c.setTheme(src.getTheme()); c.setTags(src.getTags());
        return ideas.save(c);
    }

    private static void apply(ContentIdea i, IdeaRequest r) {
        i.setTitle(r.title().strip());
        i.setHook(blank(r.hook()));
        i.setScript(blank(r.script()));
        i.setFormat(r.format());
        i.setObjective(blank(r.objective()));
        i.setTheme(blank(r.theme()));
        i.setTags(ContentSupport.joinTags(r.tags()));
    }

    // ---------- Ganchos y copys ----------

    @Transactional(readOnly = true)
    public Page<ContentSnippetDto> listSnippets(String kind, String scope, String network, String objective, String theme, String tag,
                                                String q, Boolean favorite, Pageable page) {
        Long owner = support.workspace();
        return snippets.search(kind, bankScope(scope), owner, HOOK.equals(kind) ? null : blank(network), blank(objective), blank(theme),
                ContentSupport.tagLike(tag), ContentSupport.like(cutQ(q)), favorite, sorted(page)).map(ContentSupport::snippet);
    }

    @Transactional(readOnly = true)
    public ContentSnippetDto getSnippet(String kind, Long id) { return ContentSupport.snippet(visibleSnippet(kind, id, support.workspace())); }

    @Transactional
    public ContentSnippetDto createSnippet(String kind, SnippetRequest r) {
        ContentSnippet s = new ContentSnippet();
        s.setOwnerId(support.workspace());
        s.setKind(kind);
        s.setOrigin("OWN");
        apply(s, r);
        return ContentSupport.snippet(snippets.save(s));
    }

    @Transactional
    public ContentSnippetDto updateSnippet(String kind, Long id, SnippetRequest r) {
        ContentSnippet s = editableSnippet(kind, id);
        apply(s, r);
        return ContentSupport.snippet(s);
    }

    @Transactional
    public ContentSnippetDto favoriteSnippet(String kind, Long id, boolean favorite) {
        ContentSnippet s = editableSnippet(kind, id);
        s.setFavorite(favorite);
        return ContentSupport.snippet(s);
    }

    @Transactional
    public void deleteSnippet(String kind, Long id) {
        ContentSnippet s = editableSnippet(kind, id);
        items.deleteByItem(kind, s.getId());
        snippets.delete(s);
    }

    @Transactional
    public ResponseEntity<ContentSnippetDto> copySnippet(String kind, Long id) {
        Long owner = support.workspace();
        ContentSnippet src = visibleSnippet(kind, id, owner);
        if (!src.isBank()) throw new ConflictException("Este elemento ya es tuyo");
        Optional<ContentSnippet> existing = snippets.findByOwnerIdAndSourceId(owner, src.getId());
        if (existing.isPresent()) return ResponseEntity.ok(ContentSupport.snippet(existing.get()));
        ContentSnippet c = new ContentSnippet();
        c.setOwnerId(owner); c.setKind(src.getKind()); c.setOrigin("BANK"); c.setSourceId(src.getId());
        c.setText(src.getText()); c.setNetwork(src.getNetwork()); c.setCta(src.getCta()); c.setHashtags(src.getHashtags());
        c.setTheme(src.getTheme()); c.setObjective(src.getObjective()); c.setTags(src.getTags());
        return ResponseEntity.status(HttpStatus.CREATED).body(ContentSupport.snippet(snippets.save(c)));
    }

    private ContentSnippet visibleSnippet(String kind, Long id, Long owner) {
        return snippets.findById(id).filter(s -> s.getKind().equals(kind) && (s.isBank() || owner.equals(s.getOwnerId())))
                .orElseThrow(() -> new NotFoundException(HOOK.equals(kind) ? "Gancho no encontrado" : "Copy no encontrado"));
    }

    private ContentSnippet editableSnippet(String kind, Long id) {
        ContentSnippet s = visibleSnippet(kind, id, support.workspace());
        if (s.isBank()) throw new ConflictException("Los elementos del banco no se editan: cópialo antes");
        return s;
    }

    private static void apply(ContentSnippet s, SnippetRequest r) {
        String text = r.text().strip();
        boolean hook = HOOK.equals(s.getKind());
        if (hook && text.length() > 300) throw new BadRequestException("text: el gancho admite como mucho 300 caracteres");
        s.setText(text);
        s.setNetwork(hook ? null : blank(r.network()));
        s.setCta(hook ? null : blank(r.cta()));
        s.setHashtags(hook ? null : ContentSupport.joinHashtags(r.hashtags()));
        s.setTheme(blank(r.theme()));
        s.setObjective(blank(r.objective()));
        s.setTags(ContentSupport.joinTags(r.tags()));
    }

    // ---------- Colecciones ----------

    @Transactional(readOnly = true)
    public List<CollectionDto> listCollections() {
        return collections.findByOwnerIdOrderByNameAsc(support.workspace()).stream().map(this::dto).toList();
    }

    @Transactional
    public CollectionDto createCollection(CollectionRequest r) {
        Long owner = support.workspace();
        String name = r.name().strip();
        if (collections.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner, name, -1L)) throw new ConflictException("Ya tienes una colección con ese nombre");
        return dto(collections.save(new ContentCollection(owner, name)));
    }

    @Transactional
    public CollectionDto renameCollection(Long id, CollectionRequest r) {
        ContentCollection c = collection(id);
        String name = r.name().strip();
        if (collections.existsByOwnerIdAndNameIgnoreCaseAndIdNot(c.getOwnerId(), name, c.getId())) throw new ConflictException("Ya tienes una colección con ese nombre");
        c.setName(name);
        return dto(c);
    }

    @Transactional
    public void deleteCollection(Long id) {
        ContentCollection c = collection(id);
        items.deleteByCollection(c.getId());
        collections.delete(c);
    }

    @Transactional(readOnly = true)
    public Page<CollectionItemDto> collectionItems(Long id, String type, String q, Pageable page) {
        ContentCollection c = collection(id);
        String t = blank(type);
        if (t != null && !Set.of("IDEA", HOOK, COPY).contains(t)) throw new BadRequestException("type no válido");
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<ContentCollectionItem> all = items.findByCollectionIdOrderByAddedAtDesc(c.getId()).stream()
                .filter(i -> t == null || i.getItemType().equals(t)).toList();
        Map<Long, ContentIdea> ideaById = new HashMap<>();
        ideas.findAllById(all.stream().filter(i -> "IDEA".equals(i.getItemType())).map(ContentCollectionItem::getItemId).toList())
                .forEach(x -> ideaById.put(x.getId(), x));
        Map<Long, ContentSnippet> snippetById = new HashMap<>();
        snippets.findAllById(all.stream().filter(i -> !"IDEA".equals(i.getItemType())).map(ContentCollectionItem::getItemId).toList())
                .forEach(x -> snippetById.put(x.getId(), x));
        List<CollectionItemDto> out = new ArrayList<>();
        for (ContentCollectionItem i : all) {
            String title;
            if ("IDEA".equals(i.getItemType())) { ContentIdea x = ideaById.get(i.getItemId()); if (x == null) continue; title = x.getTitle(); }
            else { ContentSnippet x = snippetById.get(i.getItemId()); if (x == null) continue; title = ContentSupport.cut(x.getText(), 120); }
            if (needle != null && !title.toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(new CollectionItemDto(i.getItemType(), i.getItemId(), title, ContentSupport.odt(i.getAddedAt())));
        }
        int from = (int) Math.min(page.getOffset(), out.size()), to = Math.min(from + page.getPageSize(), out.size());
        return new PageImpl<>(out.subList(from, to), PageRequest.of(page.getPageNumber(), page.getPageSize()), out.size());
    }

    /** Idempotente. Solo elementos propios: los del banco se copian antes (409). */
    @Transactional
    public void addItem(Long id, String type, Long itemId) {
        ContentCollection c = collection(id);
        String t = itemType(type);
        boolean bank;
        if ("IDEA".equals(t)) bank = visibleIdea(itemId, c.getOwnerId()).isBank();
        else bank = visibleSnippet(t, itemId, c.getOwnerId()).isBank();
        if (bank) throw new ConflictException("Copia antes el elemento del banco para guardarlo en una colección");
        if (!items.existsById(new ContentCollectionItem.Key(c.getId(), t, itemId))) items.save(new ContentCollectionItem(c.getId(), t, itemId));
    }

    @Transactional
    public void removeItem(Long id, String type, Long itemId) {
        ContentCollection c = collection(id);
        items.deleteById(new ContentCollectionItem.Key(c.getId(), itemType(type), itemId));
    }

    private ContentCollection collection(Long id) {
        Long owner = support.workspace();
        return collections.findById(id).filter(c -> c.getOwnerId().equals(owner)).orElseThrow(() -> new NotFoundException("Colección no encontrada"));
    }

    private static String itemType(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!Set.of("IDEA", HOOK, COPY).contains(t)) throw new NotFoundException("Tipo de elemento no encontrado");
        return t;
    }

    private CollectionDto dto(ContentCollection c) {
        return new CollectionDto(c.getId(), c.getName(), items.countByCollectionId(c.getId()), ContentSupport.odt(c.getCreatedAt()));
    }

    // ---------- Apoyo ----------

    private static boolean bankScope(String scope) {
        if (scope == null || scope.isBlank() || "MINE".equals(scope)) return false;
        if ("BANK".equals(scope)) return true;
        throw new BadRequestException("scope no válido: MINE o BANK");
    }

    /** Orden por defecto: updatedAt desc (y id para que sea estable). */
    private static final Set<String> SORTABLE = Set.of("updatedAt", "createdAt", "favorite");

    private static Pageable sorted(Pageable p) {
        List<Sort.Order> orders = p.getSort().stream().filter(o -> SORTABLE.contains(o.getProperty())).toList();
        Sort s = orders.isEmpty() ? Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")) : Sort.by(orders).and(Sort.by("id").descending());
        return PageRequest.of(p.getPageNumber(), Math.min(p.getPageSize(), 100), s);
    }

    private static String cutQ(String q) {
        if (q != null && q.length() > 100) throw new BadRequestException("q: como mucho 100 caracteres");
        return q;
    }

    private static String blank(String s) { return ContentSupport.blankToNull(s); }
}
