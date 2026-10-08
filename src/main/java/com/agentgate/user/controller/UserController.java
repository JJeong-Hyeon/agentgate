package com.agentgate.user.controller;

import com.agentgate.user.dto.PasswordChangeRequest;
import com.agentgate.user.dto.UserRequest;
import com.agentgate.user.dto.UserResponse;
import com.agentgate.user.service.UserService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/api/v1/me")
    public ResponseEntity<UserResponse> me(Principal principal) {
        return ResponseEntity.ok(userService.me(principal.getName()));
    }

    @PutMapping("/api/v1/me/password")
    public ResponseEntity<Void> changePassword(Principal principal, @Valid @RequestBody PasswordChangeRequest request) {
        userService.changeOwnPassword(principal.getName(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/users")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @GetMapping("/api/v1/users")
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userService.list());
    }

    @GetMapping("/api/v1/users/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(userService.get(id));
    }

    @PutMapping("/api/v1/users/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserRequest request,
                                               Principal principal) {
        return ResponseEntity.ok(userService.update(id, request, principal.getName()));
    }

    @DeleteMapping("/api/v1/users/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        userService.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
