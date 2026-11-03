package com.physicalchange.controller;

import com.physicalchange.dto.ContentDtos.*;
import com.physicalchange.service.content.ContentLibraryService;
import com.physicalchange.service.content.ContentPostService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Ideas, ganchos, copys y colecciones (docs/api-contenido.md §2-§4). */
@RestController @RequestMapping("/api/content") @Tag(name = "Contenido")
public class ContentLibraryController {
    private static final String HOOK = ContentLibraryService.HOOK, COPY = ContentLibraryService.COPY;
    private final ContentLibraryService svc;
    private final ContentPostService posts;
    public ContentLibraryController(ContentLibraryService s, ContentPostService p) { svc = s; posts = p; }

    // ---------- Ideas ----------
    @GetMapping("/ideas")
    public Page<ContentIdeaDto> ideas(@RequestParam(required = false) String scope, @RequestParam(required = false) String format,
                                      @RequestParam(required = false) String objective, @RequestParam(required = false) String theme,
                                      @RequestParam(required = false) String tag, @RequestParam(required = false) String q,
                                      @RequestParam(required = false) Boolean favorite, @RequestParam(required = false) Boolean used,
                                      Pageable page) {
        return svc.listIdeas(scope, format, objective, theme, tag, q, favorite, used, page);
    }
    @GetMapping("/ideas/{id}") public ContentIdeaDto idea(@PathVariable Long id) { return svc.getIdea(id); }
    @PostMapping("/ideas") @ResponseStatus(HttpStatus.CREATED) public ContentIdeaDto createIdea(@Valid @RequestBody IdeaRequest r) { return svc.createIdea(r); }
    @PutMapping("/ideas/{id}") public ContentIdeaDto updateIdea(@PathVariable Long id, @Valid @RequestBody IdeaRequest r) { return svc.updateIdea(id, r); }
    @PatchMapping("/ideas/{id}") public ContentIdeaDto patchIdea(@PathVariable Long id, @RequestBody IdeaPatchRequest r) { return svc.patchIdea(id, r); }
    @DeleteMapping("/ideas/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteIdea(@PathVariable Long id) { svc.deleteIdea(id); }
    @PostMapping("/ideas/{id}/copy") public ResponseEntity<ContentIdeaDto> copyIdea(@PathVariable Long id) { return svc.copyIdea(id); }
    @PostMapping("/ideas/{id}/to-post") @ResponseStatus(HttpStatus.CREATED)
    public ContentPostDto toPost(@PathVariable Long id, @Valid @RequestBody(required = false) ToPostRequest r) { return posts.fromIdea(id, r); }

    // ---------- Ganchos ----------
    @GetMapping("/hooks")
    public Page<ContentSnippetDto> hooks(@RequestParam(required = false) String scope, @RequestParam(required = false) String objective,
                                         @RequestParam(required = false) String theme, @RequestParam(required = false) String tag,
                                         @RequestParam(required = false) String q, @RequestParam(required = false) Boolean favorite, Pageable page) {
        return svc.listSnippets(HOOK, scope, null, objective, theme, tag, q, favorite, page);
    }
    @GetMapping("/hooks/{id}") public ContentSnippetDto hook(@PathVariable Long id) { return svc.getSnippet(HOOK, id); }
    @PostMapping("/hooks") @ResponseStatus(HttpStatus.CREATED) public ContentSnippetDto createHook(@Valid @RequestBody SnippetRequest r) { return svc.createSnippet(HOOK, r); }
    @PutMapping("/hooks/{id}") public ContentSnippetDto updateHook(@PathVariable Long id, @Valid @RequestBody SnippetRequest r) { return svc.updateSnippet(HOOK, id, r); }
    @PatchMapping("/hooks/{id}") public ContentSnippetDto favHook(@PathVariable Long id, @Valid @RequestBody FavoriteRequest r) { return svc.favoriteSnippet(HOOK, id, r.favorite()); }
    @DeleteMapping("/hooks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteHook(@PathVariable Long id) { svc.deleteSnippet(HOOK, id); }
    @PostMapping("/hooks/{id}/copy") public ResponseEntity<ContentSnippetDto> copyHook(@PathVariable Long id) { return svc.copySnippet(HOOK, id); }

    // ---------- Copys ----------
    @GetMapping("/copies")
    public Page<ContentSnippetDto> copies(@RequestParam(required = false) String scope, @RequestParam(required = false) String network,
                                          @RequestParam(required = false) String objective, @RequestParam(required = false) String theme,
                                          @RequestParam(required = false) String tag, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Boolean favorite, Pageable page) {
        return svc.listSnippets(COPY, scope, network, objective, theme, tag, q, favorite, page);
    }
    @GetMapping("/copies/{id}") public ContentSnippetDto copy(@PathVariable Long id) { return svc.getSnippet(COPY, id); }
    @PostMapping("/copies") @ResponseStatus(HttpStatus.CREATED) public ContentSnippetDto createCopy(@Valid @RequestBody SnippetRequest r) { return svc.createSnippet(COPY, r); }
    @PutMapping("/copies/{id}") public ContentSnippetDto updateCopy(@PathVariable Long id, @Valid @RequestBody SnippetRequest r) { return svc.updateSnippet(COPY, id, r); }
    @PatchMapping("/copies/{id}") public ContentSnippetDto favCopy(@PathVariable Long id, @Valid @RequestBody FavoriteRequest r) { return svc.favoriteSnippet(COPY, id, r.favorite()); }
    @DeleteMapping("/copies/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteCopy(@PathVariable Long id) { svc.deleteSnippet(COPY, id); }
    @PostMapping("/copies/{id}/copy") public ResponseEntity<ContentSnippetDto> copyCopy(@PathVariable Long id) { return svc.copySnippet(COPY, id); }

    // ---------- Colecciones ----------
    @GetMapping("/collections") public List<CollectionDto> collections() { return svc.listCollections(); }
    @PostMapping("/collections") @ResponseStatus(HttpStatus.CREATED)
    public CollectionDto createCollection(@Valid @RequestBody CollectionRequest r) { return svc.createCollection(r); }
    @PutMapping("/collections/{id}") public CollectionDto renameCollection(@PathVariable Long id, @Valid @RequestBody CollectionRequest r) { return svc.renameCollection(id, r); }
    @DeleteMapping("/collections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteCollection(@PathVariable Long id) { svc.deleteCollection(id); }
    @GetMapping("/collections/{id}/items")
    public Page<CollectionItemDto> items(@PathVariable Long id, @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q, Pageable page) {
        return svc.collectionItems(id, type, q, page);
    }
    @PutMapping("/collections/{id}/items/{type}/{itemId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addItem(@PathVariable Long id, @PathVariable String type, @PathVariable Long itemId) { svc.addItem(id, type, itemId); }
    @DeleteMapping("/collections/{id}/items/{type}/{itemId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable Long id, @PathVariable String type, @PathVariable Long itemId) { svc.removeItem(id, type, itemId); }
}
