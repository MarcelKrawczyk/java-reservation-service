package com.example.reservationService.repository;

import com.example.reservationService.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);
    List<Reservation> findByServiceOfferingProviderId(Long providerId);
    List<Reservation> findByProviderIdAndReservationTime(Long providerId, LocalDateTime time);
    List<Reservation> findByUserIdAndReservationTime(Long userId, LocalDateTime time);
    boolean existsByProviderIdAndReservationTime(Long providerId, LocalDateTime time);
    boolean existsByUserIdAndReservationTime(Long userId, LocalDateTime time);
}