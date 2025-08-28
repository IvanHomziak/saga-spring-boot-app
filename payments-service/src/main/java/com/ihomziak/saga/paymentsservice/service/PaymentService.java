package com.ihomziak.saga.paymentsservice.service;

import com.ihomziak.core.dto.Payment;

import java.util.List;

public interface PaymentService {
    List<Payment> findAll();

    Payment process(Payment payment);
}
