package com.trainsystemutilities.network;

import java.util.UUID;

/** A destination a ticket vending machine sells (OpenTicketVendingPayload.Dest until MANTA_7_CONCEPT C4). */
public record VendingDest(UUID id, String name) {
}
