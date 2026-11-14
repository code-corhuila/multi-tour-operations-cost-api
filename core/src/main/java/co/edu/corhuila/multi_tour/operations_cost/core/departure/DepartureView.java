package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import co.edu.corhuila.multi_tour.operations_cost.core.vehicle.Vehicle;
import java.util.List;

/**
 * A departure as it is answered: with its effective capacity (computed, never stored), the capacity of its tour and of its vehicles,
 * whether the tour requires transport, the seats held as commercial-reservation keeps them, and its vehicles.
 */
public record DepartureView(
    Departure departure, int capacity, int tourCapacity, int vehicleCapacity, boolean transportRequired, int booked, List<Vehicle> vehicles) {}
