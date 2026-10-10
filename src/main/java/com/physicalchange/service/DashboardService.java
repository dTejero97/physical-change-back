package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardService {
    private final UserRepository users; private final AppointmentRepository appts;
    private final WorkoutAssignmentRepository assigns; private final ProgressEntryRepository progress; private final AccessService access;
    public DashboardService(UserRepository u, AppointmentRepository a, WorkoutAssignmentRepository w, ProgressEntryRepository p, AccessService ac) {
        users = u; appts = a; assigns = w; progress = p; access = ac;
    }

    @Transactional(readOnly = true)
    public TrainerDashboard trainer() {
        AuthUser me = access.current();
        long clients = users.findByRoleAndTrainerId(Role.CLIENT, me.id(), PageRequest.of(0, 1)).getTotalElements();
        long upcoming = appts.countByTrainerIdAndStatusAndStartAtAfter(me.id(), "BOOKED", LocalDateTime.now());
        return new TrainerDashboard(clients, upcoming);
    }

    @Transactional(readOnly = true)
    public ClientDashboard client() {
        AuthUser me = access.current();
        List<ProgressEntry> h = progress.findByClientIdOrderByEntryDateAsc(me.id());
        return new ClientDashboard(assigns.countByClientIdAndCompletedDateIsNull(me.id()),
                assigns.countByClientIdAndCompletedDateIsNotNull(me.id()),
                h.isEmpty() ? null : h.get(h.size() - 1).getWeightKg());
    }
}
