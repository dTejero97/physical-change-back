package com.physicalchange.service;

import com.physicalchange.domain.*;
import com.physicalchange.dto.Dtos.*;
import com.physicalchange.repository.*;
import com.physicalchange.security.*;
import com.physicalchange.util.TrainingMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Informe resumen del entrenador: clientes, citas, cumplimiento de rutinas y cobros. */
@Service
public class ReportService {
    private final UserRepository users;
    private final AppointmentRepository appointments;
    private final WorkoutAssignmentRepository assignments;
    private final PaymentRepository payments;
    private final AccessService access;

    public ReportService(UserRepository u, AppointmentRepository a, WorkoutAssignmentRepository w,
                         PaymentRepository p, AccessService ac) {
        users = u; appointments = a; assignments = w; payments = p; access = ac;
    }

    @Transactional(readOnly = true)
    public TrainerReportDto trainer() {
        long me = access.current().id();
        long clients = users.findByRoleAndTrainerId(Role.CLIENT, me).size();
        long upcoming = appointments.countByTrainerIdAndStatusAndStartAtAfter(me, "BOOKED", LocalDateTime.now());
        long cancelled = appointments.countByTrainerIdAndStatus(me, "CANCELLED");
        List<WorkoutAssignment> all = assignments.findByTrainer(me);
        long completed = all.stream().filter(a -> a.getCompletedDate() != null).count();
        return new TrainerReportDto(clients, upcoming, cancelled, all.size(), completed,
                TrainingMath.percent((int) completed, all.size()),
                payments.sumByTrainerAndStatus(me, "PAID"),
                payments.sumByTrainerAndStatus(me, "PENDING"),
                payments.countOverdue(me, LocalDate.now()));
    }
}
