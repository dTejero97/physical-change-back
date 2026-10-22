package com.physicalchange.controller;

import com.physicalchange.dto.Dtos.*;
import com.physicalchange.service.CommunityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Grupos: los gestiona el entrenador; los clientes miembros leen, reaccionan y publican si el grupo lo permite. */
@RestController @RequestMapping("/api/community") @Tag(name = "Comunidad")
public class CommunityController {
    private final CommunityService svc;
    public CommunityController(CommunityService s) { svc = s; }

    @GetMapping("/groups") public List<GroupDto> groups() { return svc.list(); }

    @PostMapping("/groups") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.CREATED)
    public GroupDto create(@Valid @RequestBody GroupRequest r) { return svc.create(r); }

    @PutMapping("/groups/{id}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')")
    public GroupDto update(@PathVariable Long id, @Valid @RequestBody GroupRequest r) { return svc.update(id, r); }

    @DeleteMapping("/groups/{id}") @PreAuthorize("hasAnyRole('ADMIN','TRAINER')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }

    @GetMapping("/groups/{id}/posts") public List<PostDto> posts(@PathVariable Long id) { return svc.posts(id); }

    @PostMapping("/groups/{id}/posts") @ResponseStatus(HttpStatus.CREATED)
    public PostDto post(@PathVariable Long id, @Valid @RequestBody PostRequest r) { return svc.createPost(id, r); }

    @DeleteMapping("/posts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deletePost(@PathVariable Long id) { svc.deletePost(id); }

    @PostMapping("/posts/{id}/like") public PostDto like(@PathVariable Long id) { return svc.toggleLike(id); }
}
