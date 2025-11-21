package org.example.bot;



public class ParsedCaption {

    private  String vehicleType;
    private  String unitNumber;
    private  String repairCost;
    private  String repairType;

    public ParsedCaption(String vehicleType, String unitNumber, String repairCost, String repairType) {
        this.vehicleType = vehicleType;
        this.unitNumber = unitNumber;
        this.repairCost = repairCost;
        this.repairType = repairType;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public String getUnitNumber() {
        return unitNumber;
    }

    public String getRepairCost() {
        return repairCost;
    }

    public String getRepairType() {
        return repairType;
    }
}
