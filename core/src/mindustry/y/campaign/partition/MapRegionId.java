package mindustry.y.campaign.partition;

/**
 * Identity of one wafer die inside a sector: sector id plus center-aligned grid coordinates.
 * Grid origin {@code (0, 0)} is the die whose center coincides with the sector center
 * (Y端19/Y端52: 格心 = Sector 中心). All dies of one sector share the same
 * {@code sector.rect} orientation — grid axes never rotate between cells.
 */
public final class MapRegionId{
    public final int sectorId;
    /** Column relative to the sector-center die; 0 is center. */
    public final int gridX;
    /** Row relative to the sector-center die; 0 is center. */
    public final int gridY;

    public MapRegionId(int sectorId, int gridX, int gridY){
        this.sectorId = sectorId;
        this.gridX = gridX;
        this.gridY = gridY;
    }

    public boolean isCenter(){
        return gridX == 0 && gridY == 0;
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof MapRegionId)) return false;
        MapRegionId other = (MapRegionId)o;
        return sectorId == other.sectorId && gridX == other.gridX && gridY == other.gridY;
    }

    @Override
    public int hashCode(){
        int result = sectorId;
        result = 31 * result + gridX;
        result = 31 * result + gridY;
        return result;
    }

    @Override
    public String toString(){
        return "s" + sectorId + ":g" + gridX + "," + gridY;
    }
}
