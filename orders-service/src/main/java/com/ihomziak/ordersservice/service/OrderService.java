package com.ihomziak.ordersservice.service;

import com.ihomziak.core.dto.Order;

import java.util.UUID;

public interface OrderService {
    Order placeOrder(Order order);
    void approveOrder(UUID orderId);
}
