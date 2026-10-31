package com.example.scheduler.api;

import com.example.scheduler.service.AppointmentService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {
    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = AppointmentResponse.from(
                service.book(request.providerId(), request.startTime(), request.endTime()));
        return ResponseEntity.created(URI.create("/api/v1/appointments/" + response.id()))
                .body(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(AppointmentResponse.from(service.cancel(id)));
    }
}
