package com.ihomziak.ordersservice.service;


import com.ihomziak.core.types.OrderStatus;
import com.ihomziak.ordersservice.dto.OrderHistory;

import java.util.List;
import java.util.UUID;

public interface OrderHistoryService {
    void add(UUID orderId, OrderStatus orderStatus);

    List<OrderHistory> findByOrderId(UUID orderId);
}
