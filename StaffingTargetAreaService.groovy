package com.avantas.pop.api.service

import com.avantas.pop.api.dto.procedure.StaffingTargetAreaDTO
import com.avantas.pop.api.dto.procedure.StaffingTargetAreaSkillDTO
import com.avantas.pop.api.dto.procedure.StaffingTargetUnitDTO
import com.avantas.pop.api.repository.common.SkillRepository
import com.avantas.pop.api.repository.common.UnitAreaRepository
import com.avantas.pop.api.repository.common.UnitRepository
import com.avantas.pop.api.repository.procedure.StaffingTargetAreaRepository
import com.avantas.pop.api.repository.procedure.StaffingTargetAreaSkillRepository
import com.avantas.pop.api.repository.procedure.StaffingTargetUnitRepository
import com.avantas.pop.api.utils.TimeUtils
import com.avantas.smartsquare.entity.User
import com.avantas.smartsquare.entity.procedure.StaffingTargetArea
import com.avantas.smartsquare.entity.procedure.StaffingTargetAreaSkill
import com.avantas.smartsquare.entity.procedure.StaffingTargetUnit
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Slf4j
@Service
@Transactional
class StaffingTargetAreaService {

    @Autowired
    StaffingTargetUnitRepository staffingTargetUnitRepository

    @Autowired
    StaffingTargetAreaRepository staffingTargetAreaRepository

    @Autowired
    StaffingTargetAreaSkillRepository staffingTargetAreaSkillRepository

    @Autowired
    UnitRepository unitRepository

    @Autowired
    UnitAreaRepository unitAreaRepository

    @Autowired
    SkillRepository skillRepository

    /**
     * Load the Staffing Target Unit for an OEM and Unit ID.
     *
     * @param oemId
     * @return StaffingTargetUnitDTO
     */
    StaffingTargetUnitDTO loadStaffingTargetUnit(Short oemId, Long unitId) {
        StaffingTargetUnit staffingTargetUnit = staffingTargetUnitRepository.findByOemIdAndUnitId(oemId, unitId)
        if (!staffingTargetUnit) {
            return new StaffingTargetUnitDTO()
        }
        return new StaffingTargetUnitDTO(staffingTargetUnit)
    }

    /**
     * Create or update the Staffing Target Unit.
     *
     * @param oemId
     * @param staffingTargetUnitDTO
     * @return StaffingTargetUnitDTO
     */
    StaffingTargetUnitDTO createOrUpdateStaffingTargetUnit(User user, StaffingTargetUnitDTO staffingTargetUnitDTO) {
        StaffingTargetUnit staffingTargetUnit

        if (staffingTargetUnitDTO.id) {
            staffingTargetUnit = staffingTargetUnitRepository.findByIdAndOemId(staffingTargetUnitDTO.id, user.oemId)
        } else {
            staffingTargetUnit = new StaffingTargetUnit(oemId: user.oemId)
        }
        staffingTargetUnit.with {
            unit = unitRepository.findOne(staffingTargetUnitDTO.unit.id)
            modAccountId = user.id
            sunday = staffingTargetUnitDTO.sunday
            monday = staffingTargetUnitDTO.monday
            tuesday = staffingTargetUnitDTO.tuesday
            wednesday = staffingTargetUnitDTO.wednesday
            thursday = staffingTargetUnitDTO.thursday
            friday = staffingTargetUnitDTO.friday
            saturday = staffingTargetUnitDTO.saturday
            startTime = staffingTargetUnitDTO.startTime
            endTime = staffingTargetUnitDTO.endTime
            modDate = new Date()
        }
        staffingTargetUnit = staffingTargetUnitRepository.save(staffingTargetUnit)

        return new StaffingTargetUnitDTO(staffingTargetUnit)
    }

    /**
     * Create or update the Staffing Target Area.
     *
     * @param oemId
     * @param staffingTargetAreaDTO
     * @return StaffingTargetAreaDTO
     */
    StaffingTargetAreaDTO createOrUpdateStaffingTargetArea(User user, StaffingTargetAreaDTO staffingTargetAreaDTO) {
        StaffingTargetArea staffingTargetArea

        if (staffingTargetAreaDTO.id) {
            staffingTargetArea = staffingTargetAreaRepository.findByIdAndOemId(staffingTargetAreaDTO.id, user.oemId)
        } else {
            staffingTargetArea = new StaffingTargetArea(oemId: user.oemId)
        }
        if (staffingTargetAreaDTO.unitArea) {
            staffingTargetArea.unitArea = unitAreaRepository.findOne(staffingTargetAreaDTO.unitArea.id)
        }
        staffingTargetArea.modAccountId = user.id
        staffingTargetArea.modDate = new Date()
        staffingTargetArea.startTime = staffingTargetAreaDTO.startTime
        staffingTargetArea.endTime = staffingTargetAreaDTO.endTime
        staffingTargetArea.staffingTargetUnit = staffingTargetUnitRepository.findOne(staffingTargetAreaDTO.staffingTargetUnitId)
        staffingTargetArea = staffingTargetAreaRepository.save(staffingTargetArea)

        return new StaffingTargetAreaDTO(staffingTargetArea)
    }

    /**
     * Create or update the Staffing Target Area.
     *
     * @param oemId
     * @param staffingTargetAreaDTO
     * @return List<StaffingTargetAreaDTO>
     */
    List<StaffingTargetAreaDTO> createStaffingTargetAreas(User user, List<StaffingTargetAreaDTO> staffingTargetAreaDTOs) {
        return staffingTargetAreaDTOs.findResults {
            createOrUpdateStaffingTargetArea(user, it)
        }
    }

    /**
     * Create or update the Staffing Target Area Skill.
     *
     * @param user
     * @param staffingTargetAreaSkillDTO
     * @return StaffingTargetAreaSkillDTO
     */
    StaffingTargetAreaSkillDTO createOrUpdateStaffingTargetAreaSkill(User user, StaffingTargetAreaDTO staffingTargetAreaDTO) {
        StaffingTargetAreaSkill staffingTargetAreaSkill

        if (staffingTargetAreaDTO.staffingTargetAreaSkills?.first()?.id) {
            staffingTargetAreaSkill = staffingTargetAreaSkillRepository.findByIdAndOemId(staffingTargetAreaDTO.getStaffingTargetAreaSkills()[0].id, user.oemId)
        } else {
            staffingTargetAreaSkill = new StaffingTargetAreaSkill(oemId: user.oemId)
        }
        staffingTargetAreaSkill.staffingTargetArea = staffingTargetAreaRepository.findOne(staffingTargetAreaDTO.id)
        staffingTargetAreaSkill.skill = skillRepository.findOne(staffingTargetAreaDTO.staffingTargetAreaSkills?.first()?.skill.id)
        staffingTargetAreaSkill.amount = staffingTargetAreaDTO.getStaffingTargetAreaSkills()?.first()?.amount
        staffingTargetAreaSkill.modAccountId = user.id
        staffingTargetAreaSkill.modDate = new Date()
        staffingTargetAreaSkill = staffingTargetAreaSkillRepository.save(staffingTargetAreaSkill)

        return new StaffingTargetAreaSkillDTO(staffingTargetAreaSkill)
    }

    /**
     * Delete the Staffing Target Area.
     *
     * @param staffingTargetAreaId
     * @param oemId
     */
    void deleteStaffingTargetArea(Long staffingTargetAreaId, Short oemId) {
        StaffingTargetArea staffingTargetArea = staffingTargetAreaRepository.findByIdAndOemId(staffingTargetAreaId, oemId)
        staffingTargetAreaSkillRepository.deleteInBatch(staffingTargetArea.staffingTargetAreaSkills)
        staffingTargetAreaRepository.delete(staffingTargetAreaId)
    }

    /**
     * Delete the Staffing Target Areas.
     *
     * @param staffingTargetAreaDTOs
     * @param oemId
     */
    void deleteStaffingTargetAreas(List<Long> ids, Short oemId) {
        ids.each {
            deleteStaffingTargetArea(it, oemId)
        }
    }

    /**
     * Delete the Staffing Target Area Skill.
     *
     * @param staffingTargetAreaSkillId
     * @param oemId
     */
    void deleteStaffingTargetAreaSkill(Long staffingTargetAreaSkillId) {
        staffingTargetAreaSkillRepository.delete(staffingTargetAreaSkillId)
    }

    /**
     * Delete the Staffing Target Area Skill by Skill Id.
     *
     * @param skillId
     */
    void deleteStaffingTargetAreaSkillBySkillId(Long skillId) {
        staffingTargetAreaSkillRepository.deleteBySkillId(skillId)
    }

    /**
     * Return start and end LocalDateTime objects for the staffing target are on date provided.
     *
     * @param staffingTargetArea
     * @param date
     * @return
     */
    static List getStaffingTargetAreaStartAndEndTimes(StaffingTargetArea staffingTargetArea, LocalDate date) {
        Long startTime = staffingTargetArea.startTime == null ? staffingTargetArea.staffingTargetUnit.startTime : staffingTargetArea.startTime
        Long endTime = staffingTargetArea.endTime == null ? staffingTargetArea.staffingTargetUnit.endTime : staffingTargetArea.endTime
        boolean nextDay = startTime >= endTime

        Long[] startTimeIndex = TimeUtils.getHoursAndMinutesFromLong(startTime)
        Long[] endTimeIndex = TimeUtils.getHoursAndMinutesFromLong(endTime)
        LocalDateTime startDateTime = date.atStartOfDay().plusHours(startTimeIndex[0]).plusMinutes(startTimeIndex[1])
        LocalDateTime endDateTime = date.atStartOfDay().plusHours(endTimeIndex[0]).plusMinutes(endTimeIndex[1]).plusDays(nextDay ? 1 : 0)

        return [startDateTime, endDateTime]
    }

    /**
     * Determine if Staffing Target Area's Unit is configured for date provided.
     *
     * @param staffingTargetArea
     * @param localDate
     * @return
     */
    static Boolean activeForDay(StaffingTargetUnit staffingTargetUnit, LocalDate date) {
        switch (date.dayOfWeek) {
            case DayOfWeek.SUNDAY:
                return staffingTargetUnit.sunday
            case DayOfWeek.MONDAY:
                return staffingTargetUnit.monday
            case DayOfWeek.TUESDAY:
                return staffingTargetUnit.tuesday
            case DayOfWeek.WEDNESDAY:
                return staffingTargetUnit.wednesday
            case DayOfWeek.THURSDAY:
                return staffingTargetUnit.thursday
            case DayOfWeek.FRIDAY:
                return staffingTargetUnit.friday
            case DayOfWeek.SATURDAY:
                return staffingTargetUnit.saturday
        }
    }


}