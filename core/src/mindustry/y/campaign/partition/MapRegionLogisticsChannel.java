package mindustry.y.campaign.partition;

/**
 * Minimal MapRegion-level logistics channel kinds (J4 optional layer).
 * <p>
 * These describe <em>what may cross</em> a registered adjacency edge under WAFER;
 * they do not touch Sector strategic accounts or shared-campaign transport.
 * Traditional adjacency is empty, so no channel is ever offered there.
 */
public enum MapRegionLogisticsChannel{
    /** Unit movement across a MapRegion edge (same sector die grid). */
    UNIT,
    /** Item logistics across a MapRegion edge (same sector die grid). */
    ITEM
}
