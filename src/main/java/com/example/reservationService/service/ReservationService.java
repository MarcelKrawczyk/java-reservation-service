package com.example.reservationService.service;

import com.example.reservationService.exception.ConflictException;
import com.example.reservationService.exception.NotFoundException;
import com.example.reservationService.exception.ValidationException;
import com.example.reservationService.model.Provider;
import com.example.reservationService.model.Reservation;
import com.example.reservationService.model.ServiceOffering;
import com.example.reservationService.model.User;
import com.example.reservationService.repository.ProviderRepository;
import com.example.reservationService.repository.ReservationRepository;
import com.example.reservationService.repository.ServiceOfferingRepository;
import com.example.reservationService.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final ServiceOfferingRepository serviceOfferingRepository;

    public ReservationService(ReservationRepository reservationRepository,
                              UserRepository userRepository,
                              ProviderRepository providerRepository,
                              ServiceOfferingRepository serviceOfferingRepository) {
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.providerRepository = providerRepository;
        this.serviceOfferingRepository = serviceOfferingRepository;
    }

    public Reservation create(Reservation reservation) {

        User user = userRepository.findById(reservation.getUser().getId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        Provider provider = providerRepository.findById(reservation.getProvider().getId())
                .orElseThrow(() -> new NotFoundException("Provider not found"));

        ServiceOffering offering = serviceOfferingRepository.findById(reservation.getServiceOffering().getId())
                .orElseThrow(() -> new NotFoundException("Service offering not found"));

        if (!offering.getProvider().getId().equals(provider.getId())) {
            throw new ValidationException("This service does not belong to the selected provider");
        }

        if (reservation.getReservationTime().isBefore(LocalDateTime.now())) {
            throw new ValidationException("Reservation cannot be in the past");
        }

        boolean providerBusy = reservationRepository.existsByProviderIdAndReservationTime(provider.getId(), reservation.getReservationTime());

        if (providerBusy) {
            throw new ConflictException("Provider is not available at this time");
        }

        boolean userBusy = reservationRepository.existsByUserIdAndReservationTime(user.getId(), reservation.getReservationTime());

        if (userBusy) {
            throw new ConflictException("User already has a reservation at this time");
        }
        reservation.setUser(user);
        reservation.setProvider(provider);
        reservation.setServiceOffering(offering);

        return reservationRepository.save(reservation);
    }

    public List<Reservation> getAll() {
        return reservationRepository.findAll();
    }

    public Reservation getById(Long id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
    }

    public List<Reservation> getByClientId(Long clientId) {
        return reservationRepository.findByUserId(clientId);
    }

    public List<Reservation> getByProviderId(Long providerId) {
        return reservationRepository.findByServiceOfferingProviderId(providerId);
    }
}
