package com.avantas.pop.api.dto.common

import com.avantas.smartsquare.entity.StaffManagerUnit
import com.avantas.smartsquare.entity.unit.unitGroup.UnitGroupUnit

class StaffManagerUnitGroupUnitDTO extends StaffManagerUnitDTO {

    Long priority
    boolean isPrimaryUnit
    boolean isFloatUnit

    StaffManagerUnitGroupUnitDTO(StaffManagerUnit unit, UnitGroupUnit unitGroupUnit) {
        super(unit)
        this.priority = unitGroupUnit?.priority
        this.isPrimaryUnit = unitGroupUnit?.isPrimaryUnit
        this.isFloatUnit =  !unitGroupUnit?.unit?.costCenter?.partnership?.staffAsConfirmed
    }

    StaffManagerUnitGroupUnitDTO(UnitGroupUnit unitGroupUnit) {
        super(unitGroupUnit.unit)
        this.priority = unitGroupUnit.priority
        this.isPrimaryUnit = unitGroupUnit.isPrimaryUnit
        this.isFloatUnit =  !unitGroupUnit?.unit?.costCenter?.partnership?.staffAsConfirmed
    }
}
