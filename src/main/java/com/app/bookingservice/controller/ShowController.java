package com.app.bookingservice.controller;

import com.app.bookingservice.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Creating a show is admin-only; viewing needs any valid token (see SecurityConfig). */
@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        return ShowResponse.from(showService.create(request.name(), request.seats(), request.pricePaise()));
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return ShowResponse.from(showService.get(id));
    }
}
