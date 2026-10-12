package md.utm.telecom.tracelink.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class InventoryResponses {
    private InventoryResponses() {}

    public record CityList(String catalogueVersion, List<City> cities) {}
    public record City(String id, String name, BigDecimal latitude, BigDecimal longitude,
                       BigDecimal demandShare, boolean active, Instant validFrom,
                       Instant validTo, boolean synthetic) {}
    public record LinkList(String catalogueVersion, List<Link> links) {}
    public record Link(String id, String endpointA, String endpointB, String linkType,
                       BigDecimal nominalCapacityGbps, boolean active, Instant validFrom,
                       Instant validTo, List<LinkDirection> directions, boolean synthetic) {}
    public record LinkDirection(String id, String sourceInterface, String destinationInterface,
                                BigDecimal effectiveCapacityGbps, Instant capacityValidFrom,
                                Instant capacityValidTo, String measurementSource) {}
    public record PartnerList(String catalogueVersion, List<Partner> partners) {}
    public record Partner(String id, String country, String direction, boolean active,
                          Instant validFrom, Instant validTo, boolean synthetic) {}
}
