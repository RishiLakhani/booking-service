package com.app.bookingservice.controller;

import com.app.bookingservice.observability.SeatsAvailableGauges;
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
    private final SeatsAvailableGauges seatsAvailableGauges;

    public ShowController(ShowService showService, SeatsAvailableGauges seatsAvailableGauges) {
        this.showService = showService;
        this.seatsAvailableGauges = seatsAvailableGauges;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        var state = showService.create(request.name(), request.seats(), request.pricePaise());
        seatsAvailableGauges.register(state.show().id());
        return ShowResponse.from(state);
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return ShowResponse.from(showService.get(id));
    }
}
