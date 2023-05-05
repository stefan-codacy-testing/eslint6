package com.avantas.pop.api.service

import com.avantas.pop.api.dto.excuse.ScheduleExcuseDTO
import com.avantas.pop.api.dto.excuse.ScheduleExcuseRequestDTO
import com.avantas.pop.api.dto.excuse.ScheduleExcuseTypeDTO
import com.avantas.pop.api.dto.schedule.AccountScheduleStitchedDTO
import com.avantas.pop.api.repository.common.PayRuleRepository
import com.avantas.pop.api.repository.common.ScheduleExcuseTypeRepository
import com.avantas.pop.api.repository.common.SiteOptionRepository
import com.avantas.pop.api.repository.common.StaffManagerUnitRepository
import com.avantas.pop.api.repository.schedule.SchAcctScheduleExcuseRepository
import com.avantas.pop.api.repository.schedule.SchAcctScheduleRepository
import com.avantas.pop.api.repository.user.UserRepository
import com.avantas.pop.api.repository.util.UtilsRepository
import com.avantas.smartsquare.entity.PayRule
import com.avantas.smartsquare.entity.SchAcctSchedule
import com.avantas.smartsquare.entity.SchAcctScheduleExcuse
import com.avantas.smartsquare.entity.ScheduleExcuseType
import com.avantas.smartsquare.entity.User
import com.avantas.smartsquare.entity.unit.Unit
import com.avantas.smartsquare.enums.CostCenterAccessMode
import com.avantas.smartsquare.enums.ScheduleExcuseStatus
import com.avantas.smartsquare.enums.ScheduleExcuseTypeKeys
import com.avantas.smartsquare.enums.SiteOptionTypes
import com.google.common.collect.Iterables
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Slf4j
@Service
@Transactional
class ScheduleExcuseService {
    @Autowired
    SchAcctScheduleExcuseRepository scheduleExcusesRepository

    @Autowired
    ScheduleExcuseTypeRepository scheduleExcuseTypeRepository

    @Autowired
    UserRepository userRepository

    @Autowired
    SchAcctScheduleRepository scheduleRepository

    @Autowired
    StaffManagerUnitRepository staffManagerUnitRepository

    @Autowired
    SiteOptionRepository siteOptionRepository

    @Autowired
    ScheduleService scheduleService

    @Autowired
    PayRuleRepository payRuleRepository

    @Autowired
    UtilsRepository utilsRepository

    List<ScheduleExcuseTypeDTO> getExcuseTypesList() {
        List<ScheduleExcuseType> excuseTypes = scheduleExcuseTypeRepository.findAll()
        if (excuseTypes) {
            return excuseTypes.sort { it.displayOrder }.findResults { new ScheduleExcuseTypeDTO(it) }
        }
        return []

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deleteExcusesForScheduleIds(Short oemId, List<Long> scheduleIds) {

        List<SchAcctScheduleExcuse> excuses = []
        Iterables.partition(scheduleIds, 999).each { scheduleIdList ->
            excuses.addAll(scheduleExcusesRepository.findAllByOemIdAndScheduleIdIn(oemId, scheduleIdList))
        }

        if (excuses?.size() > 0) {
            scheduleExcusesRepository.deleteInBatch(excuses)
            scheduleExcusesRepository.flush()
            log.debug("Deleting schedule excuses for $scheduleIds ")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void saveScheduleExcuses(List<SchAcctScheduleExcuse> scheduleExcuses) {
        if (scheduleExcuses?.size() > 0) {
            scheduleExcusesRepository.save(scheduleExcuses)
            log.debug("Saving schedule excuses for " + scheduleExcuses.collect { it.id }.toString())
        }
    }

    /**
     * Will update excuses for the Daily Schedule screen
     * This update is for a single scheduled shift but can include stitched schedule ids
     * Required to have a single schedule or multiple schedule ids
     * If no excuse types are provided then delete all schedule excuses for these schedule ids
     * If scheduleids and excuse types are provided then loop the schedule items and process the excuses
     * based on what excuse types were sent in the request
     *
     *
     * @param oemId
     * @param excuseDto
     * @param modAccountId
     */
    Collection<ScheduleExcuseDTO> updateScheduleExcuses(Short oemId, ScheduleExcuseRequestDTO excuseDto, Long modAccountId) {
        Collection<SchAcctScheduleExcuse> excusesList = []
        List<Long> scheduleIds = excuseDto.scheduleIds
        Collection<ScheduleExcuseTypeDTO> excusesSetByUser = excuseDto.scheduleExcuses

        String tempSchAcctScheduleIdKey = utilsRepository.insertTempIds(scheduleIds)
        List<SchAcctSchedule> schedules = scheduleRepository.findByOemIdAndIdIn(oemId, tempSchAcctScheduleIdKey)

        try {
            if (excusesSetByUser?.size() > 0 && schedules?.size() > 0) {
                User modUser = userRepository.findByIdAndOemId(modAccountId, oemId)

                List<SchAcctScheduleExcuse> currentExcuses = []
                Iterables.partition(scheduleIds, 999).each { scheduleIdList ->
                    currentExcuses.addAll(scheduleExcusesRepository.findAllByOemIdAndScheduleIdIn(oemId, scheduleIdList))
                }

                for (Long scheduleId : scheduleIds) {
                    SchAcctSchedule schedule = schedules.find { it.id == scheduleId }
                    processScheduleExcuses(excusesList, excusesSetByUser, currentExcuses, schedule, modUser)
                }
            } else if (schedules?.size() > 0) {
                deleteExcusesForScheduleIds(oemId, scheduleIds)
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("510:Not able to save schedules excuses, please try again. Error: $ex.message")
        }
        return excusesList.findResults { new ScheduleExcuseDTO(it) }
    }

    /**
     * Loop through the 4 current excuse types and update/insert/delete schedule excuses based on what excuse types were sent in ScheduleExcuseTypeDTO
     *
     * @param modUser
     * @param schedule
     * @param excuseList
     * @param excusesSetByUser
     * @param currentExcuses
     * @return
     */
    Collection<SchAcctScheduleExcuse> processScheduleExcuses(Collection<SchAcctScheduleExcuse> excuseList, Collection<ScheduleExcuseTypeDTO> excusesSetByUser, List<SchAcctScheduleExcuse> currentExcuses, SchAcctSchedule schedule, User modUser) {
        if (currentExcuses) {
            Collection<ScheduleExcuseType> excuseTypes = scheduleExcuseTypeRepository.findAll()
            excuseTypes.forEach { excuseType ->
                processExcuse(
                        excuseList,
                        currentExcuses.find { ex -> ex.excuseType == excuseType && ex.scheduleId == schedule.id },
                        excusesSetByUser.find { it.excuseType == excuseType.key.toString() },
                        schedule,
                        modUser
                )
            }
        } else {
            excuseList.addAll(insertNewExcuses(excusesSetByUser, schedule, modUser))
        }
        return excuseList
    }

    /**
     * Insert, update or delete excuse record based on excusesSetByUser and current schedule excuses
     * @param currentExcuse
     * @param excusesSetByUser
     * @param schedule
     * @param modUser
     * @return
     */
    Collection<SchAcctScheduleExcuse> processExcuse(Collection<SchAcctScheduleExcuse> excuseList, SchAcctScheduleExcuse currentExcuse, ScheduleExcuseTypeDTO excusesSetByUser, SchAcctSchedule schedule, User modUser) {
        if (!excusesSetByUser && currentExcuse) {
            scheduleExcusesRepository.delete(currentExcuse.id)
        } else if (excusesSetByUser && currentExcuse) {
            SchAcctScheduleExcuse excuseToUpdate = currentExcuse
            if (currentExcuse.payRule?.id != excusesSetByUser.incentiveId || currentExcuse.status != ScheduleExcuseStatus.EXCUSED) {
                PayRule payRule = excusesSetByUser.incentiveId ? payRuleRepository.findByIdAndOemId(excusesSetByUser.incentiveId, currentExcuse.oemId) : null
                excuseToUpdate.payRule = payRule ?: excuseToUpdate.payRule
                excuseToUpdate.status = ScheduleExcuseStatus.EXCUSED
                scheduleExcusesRepository.save(excuseToUpdate)
            }
            excuseList.add(excuseToUpdate)
        } else if (excusesSetByUser) {
            excuseList.addAll(insertNewExcuses([excusesSetByUser], schedule, modUser))
        }
        return excuseList
    }

    /**
     * create new SchAcctScheduleExcuse(s) and save via scheduleExcusesRepository.save
     * @param scheduleExcuses
     * @param schedule
     * @param modUser
     * @return
     */
    Collection<SchAcctScheduleExcuse> insertNewExcuses(Collection<ScheduleExcuseTypeDTO> scheduleExcuses, SchAcctSchedule schedule, User modUser) {
        Collection<SchAcctScheduleExcuse> results = []
        scheduleExcuses.each { excuse ->
            if (excuse.excuseType) {
                ScheduleExcuseTypeKeys key = ScheduleExcuseTypeKeys.valueOf(excuse.excuseType)
                SchAcctScheduleExcuse result = new SchAcctScheduleExcuse(
                        oemId: schedule.oemId,
                        scheduleId: schedule.id,
                        excuseType: scheduleExcuseTypeRepository.findByKey(key),
                        user: schedule.user,
                        status: ScheduleExcuseStatus.EXCUSED,
                        modUser: modUser,
                        payRule: excuse.incentiveId ? payRuleRepository.findByIdAndOemId(excuse.incentiveId, schedule.oemId) : null
                )
                saveScheduleExcuses([result])
                results.add(result)
            }
        }
        return results
    }

    /**
     * Collect the units that a elevated user/manager has access to and filter the list of staff schedules to those units
     * collect shifts where shifts are between original shift date + and - 1 day
     * Return a summary DTO for which shifts would be considered "stitched"
     * "Stitched" shifts are shifts that are ADJ.directCare = yes and within x number of minutes from the shift times of the original shift
     * @param oemId
     * @param scheduleId
     * @param manager
     * @return
     */
    AccountScheduleStitchedDTO loadStitchedSchedule(Short oemId, Long scheduleId, User manager) {
        SchAcctSchedule originalSchedule = scheduleService.getSchedule(oemId, scheduleId)

        List<Unit> managerUnits = staffManagerUnitRepository.findAllByOemIdAndAccountIdAndActiveTrueAndStartLocalDateLessThanEqualAndEndLocalDateGreaterThanEqual(manager.oemId, manager.id, originalSchedule.scheduleLocalDate, originalSchedule.scheduleLocalDate).findAll {
            it.accessMode == CostCenterAccessMode.READWRITE.toString()
        }.collect { it.unit }.toList()

        List<SchAcctSchedule> schedules = []
        if (managerUnits) {
            String tempUnitIdKey = utilsRepository.insertTempIds(managerUnits.id)
            String tempUserIdKey = utilsRepository.insertTempIds([originalSchedule.user.id])
            schedules = scheduleRepository.findActiveSchedulesInDateRangeAndInUnitsAndAdjustmentIsDirectCareForUserIdsIn(originalSchedule.oemId, originalSchedule.scheduleLocalDate.minusDays(1), originalSchedule.scheduleLocalDate.plusDays(1), tempUnitIdKey, tempUserIdKey)
        }

        return buildStitchedSchedule(originalSchedule, schedules)
    }

    /**
     * Return the AccountScheduleStitchedDTO which will include the "stitched" start and end times of all the schedules included
     * @param originalSchedule
     * @param schedules
     * @return
     */
    AccountScheduleStitchedDTO buildStitchedSchedule(SchAcctSchedule originalSchedule, List<SchAcctSchedule> schedules) {

        List<SchAcctSchedule> schedulesList = buildStitchedScheduleList(originalSchedule, schedules)

        List<SchAcctScheduleExcuse> approvedExcuses = []
        Iterables.partition(schedulesList*.id, 999).each { scheduleIdList ->
            approvedExcuses.addAll(
                    scheduleExcusesRepository.findAllByOemIdAndScheduleIdInAndStatus(originalSchedule.oemId, scheduleIdList, ScheduleExcuseStatus.EXCUSED).collect {
                        new ScheduleExcuseTypeDTO(it.excuseType, getExcuseStatus(it.status), it.payRule)
                    }
            )
        }
//        List<ScheduleExcuseTypeDTO> approvedExcuses = scheduleExcusesRepository.findAllByOemIdAndScheduleIdInAndStatus(originalSchedule.oemId, schedulesList.collect { it.id }, ScheduleExcuseStatus.EXCUSED).collect { new ScheduleExcuseTypeDTO(it.excuseType, getExcuseStatus(it.status), it.payRule) }

        return new AccountScheduleStitchedDTO(
                getDisplayTimeString(originalSchedule, schedulesList),
                schedulesList*.id,
                originalSchedule.id,
                !hasSameExcuses(originalSchedule.oemId, schedulesList),
                approvedExcuses?.unique { it.excuseType })
    }

    /**
     * find the overlapping shifts and return that list
     * @param originalSchedule
     * @param schedules
     * @return
     */
    List<SchAcctSchedule> buildStitchedScheduleList(SchAcctSchedule originalSchedule, List<SchAcctSchedule> schedules) {
        List<SchAcctSchedule> schedulesList = [originalSchedule]
        List<SchAcctSchedule> allSchedules = schedules
        allSchedules.remove(originalSchedule)

        Long bufferSeconds = siteOptionRepository.findByOemIdAndSiteOptionItemActiveTrueAndOptionName(originalSchedule.oemId, SiteOptionTypes.PUNCH_VARIANCE_PROCESSING_CONTIG_GAP.optionValue)?.optionVal?.toLong() ?: 1800

        if (allSchedules.size() > 0) {
            List<SchAcctSchedule> shiftsOver = allSchedules.findAll {
                it.fullEndLocalDateTime.isAfter(originalSchedule.fullStartLocalDateTime) && it.fullStartLocalDateTime.isBefore(originalSchedule.fullEndLocalDateTime)
            }.sort { a, b -> a.fullStartLocalDateTime <=> b.fullStartLocalDateTime }

            shiftsOver.stream().forEach { schedule ->
                if (hasScheduleOverlap(schedulesList, schedule.fullStartLocalDateTime, schedule.fullEndLocalDateTime, bufferSeconds)) {
                    schedulesList.push(schedule)
                    allSchedules.remove(schedule)
                }
            }

            List<SchAcctSchedule> shiftsAfter = allSchedules.findAll {
                it.fullEndLocalDateTime.isAfter(originalSchedule.fullStartLocalDateTime)
            }.sort { a, b -> a.fullStartLocalDateTime <=> b.fullStartLocalDateTime }

            shiftsAfter.stream().forEach { schedule ->
                if (hasScheduleOverlap(schedulesList, schedule.fullStartLocalDateTime, schedule.fullEndLocalDateTime, bufferSeconds)) {
                    schedulesList.push(schedule)
                    allSchedules.remove(schedule)
                }
            }

            List<SchAcctSchedule> shiftsBefore = allSchedules.findAll {
                it.fullStartLocalDateTime.isBefore(originalSchedule.fullEndLocalDateTime.plusSeconds(1))
            }.sort { a, b -> b.fullEndLocalDateTime <=> a.fullEndLocalDateTime }

            shiftsBefore.stream().forEach { schedule ->
                if (hasScheduleOverlap(schedulesList, schedule.fullStartLocalDateTime, schedule.fullEndLocalDateTime, bufferSeconds)) {
                    schedulesList.push(schedule)
                    schedules.remove(schedule)
                }
            }

            schedulesList.addAll(allSchedules.findAll {
                it.fullStartLocalDateTime.isAfter(originalSchedule.fullStartLocalDateTime.minusSeconds(1)) && it.fullEndLocalDateTime.isBefore(originalSchedule.fullEndLocalDateTime.plusSeconds(1))
            })
        }

        return schedulesList.sort { a, b -> a.fullStartLocalDateTime <=> b.fullStartLocalDateTime }
    }

    /**
     * check to see if all the schedules have the same excuses checked
     * @param oemId
     * @param schedules
     * @return
     */
    boolean hasSameExcuses(Short oemId, List<SchAcctSchedule> schedules) {
        Integer scheduleCount = schedules.size()
        if (scheduleCount == 1) {
            return true
        }

        List<SchAcctScheduleExcuse> excuses = []
        Iterables.partition(schedules*.id, 999).each { List<Long> scheduleIds ->
            excuses.addAll(scheduleExcusesRepository.findAllByOemIdAndScheduleIdIn(oemId, scheduleIds))
        }

        if (excuses) {
            Boolean schedulesSame = true
            for (ScheduleExcuseTypeKeys key : ScheduleExcuseTypeKeys.values()) {
                schedulesSame = schedulesSame && checkExcuses(excuses, key, scheduleCount)
            }
            return schedulesSame
        }
        return true
    }

    static Boolean checkExcuses(List<SchAcctScheduleExcuse> excuses, ScheduleExcuseTypeKeys key, Integer scheduleCount) {
        Integer keyCount = excuses.findAll { it.excuseType.key == key && getExcuseStatus(it.status) }.size()
        return keyCount == 0 || keyCount == scheduleCount
    }


    /**
     * return the start and end time as a string based on the list of schedules
     * @param schedulesList
     * @return
     */
    static List<Object> getDisplayTimeString(SchAcctSchedule originalSchedule, List<SchAcctSchedule> schedulesList) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("M/d h:mm a")

        LocalDateTime start = schedulesList.min { it.fullStartLocalDateTime }.fullStartLocalDateTime
        LocalDateTime end = schedulesList.max { it.fullEndLocalDateTime }.fullEndLocalDateTime
        Boolean showDate = !originalSchedule.scheduleLocalDate.isEqual(start.toLocalDate()) || !originalSchedule.scheduleLocalDate.isEqual(end.toLocalDate())
        String startString = start.format(showDate ? dateTimeFormatter : timeFormatter)
        String endString = end.format(showDate ? dateTimeFormatter : timeFormatter)

        return [start, end, startString, endString]
    }

    /**
     * Find if a schedule has overlap based on the scheduled full start and end times
     * @param shiftStart
     * @param shiftEnd
     * @param compareStart
     * @param compareEnd
     * @param buffer
     * @return
     */
    static boolean hasScheduleOverlap(List<SchAcctSchedule> scheduleList, LocalDateTime compareStart, LocalDateTime compareEnd, long buffer) {
        if (
                compareStart.minusSeconds(buffer + 1).isBefore(scheduleList.max { it.fullEndLocalDateTime }.fullEndLocalDateTime)
                        && compareEnd.plusSeconds(buffer + 1).isAfter(scheduleList.min { it.fullStartLocalDateTime }.fullStartLocalDateTime)
        ) {
            return true
        }
        return false
    }

    static Boolean getExcuseStatus(ScheduleExcuseStatus status) {
        return status == ScheduleExcuseStatus.EXCUSED
    }
}