package com.avantas.pop.api.service

import com.avantas.pop.api.dto.excuse.BulkEditRequestDTO
import com.avantas.pop.api.dto.excuse.BulkEditResponseDTO
import com.avantas.pop.api.dto.excuse.BulkExcuseIdsDTO
import com.avantas.pop.api.dto.excuse.ExcuseConfigDTO
import com.avantas.pop.api.dto.excuse.ExcuseTimeGridDTO
import com.avantas.pop.api.dto.excuse.ScheduleExcuseDTO
import com.avantas.pop.api.dto.punch.AccountPunchTimeDTO
import com.avantas.pop.api.dto.punch.AccountPunchTimeRowMapper
import com.avantas.pop.api.enums.ErrorCodes
import com.avantas.pop.api.enums.ManageExcuseStatus
import com.avantas.pop.api.exception.SaveScheduleExcusesException
import com.avantas.pop.api.repository.assignment.AssignmentRepository
import com.avantas.pop.api.repository.common.PayRuleRepository
import com.avantas.pop.api.repository.common.ScheduleExcuseTypeRepository
import com.avantas.pop.api.repository.common.SiteOptionRepository
import com.avantas.pop.api.repository.common.StaffManagerUnitRepository
import com.avantas.pop.api.repository.common.UnitRepository
import com.avantas.pop.api.repository.schedule.SchAcctScheduleExcuseRepository
import com.avantas.pop.api.repository.schedule.SchAcctScheduleRepository
import com.avantas.pop.api.repository.user.UserRepository
import com.avantas.pop.api.repository.util.UtilsRepository
import com.avantas.pop.api.service.user.UserUtilityService
import com.avantas.smartsquare.entity.PayRule
import com.avantas.smartsquare.entity.SchAcctSchedule
import com.avantas.smartsquare.entity.SchAcctScheduleExcuse
import com.avantas.smartsquare.entity.ScheduleExcuseType
import com.avantas.smartsquare.entity.StaffManagerUnit
import com.avantas.smartsquare.entity.User
import com.avantas.smartsquare.entity.dailyAssignment.Assignment
import com.avantas.smartsquare.entity.unit.Unit
import com.avantas.smartsquare.enums.CostCenterAccessMode
import com.avantas.smartsquare.enums.ScheduleExcuseStatus
import com.avantas.smartsquare.enums.ScheduleExcuseTypeKeys
import com.google.common.collect.Iterables
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.sql.Date
import java.time.LocalDate

@Slf4j
@Service
@Transactional
class ManageExcuseTimeService {

    @Autowired(required = true)
    NamedParameterJdbcTemplate jdbcTemplate

    @Autowired
    AssignmentRepository assignmentRepository

    @Autowired
    UserRepository userRepository

    @Autowired
    SchAcctScheduleRepository scheduleRepository

    @Autowired
    UnitRepository unitRepository

    @Autowired
    StaffManagerUnitRepository staffManagerUnitRepository

    @Autowired
    ScheduleExcuseTypeRepository scheduleExcuseTypeRepository

    @Autowired
    SchAcctScheduleExcuseRepository scheduleExcuseRepository

    @Autowired
    PayRuleRepository payRuleRepository

    @Autowired
    SiteOptionRepository siteOptionRepository

    @Autowired
    DailyScheduleService dailyScheduleService

    @Autowired
    UserUtilityService userUtilityService

    @Autowired
    UtilsRepository utilsRepository

    String punchDataSql = """
        SELECT 
                A.ACCOUNT_ID,
                A.SCHEDULE_DATE,
                A.START_DATE,
                A.END_DATE,
                A.SCH_COST_CENTER_ID,
                B.SCH_UNIT_ID_PRIMARY,
                SUM(A.TOTAL_HOURS) AS PUNCH_HOURS   
        FROM
                SHR_ACCT_TIME_PUNCH_PAY A,
                SCH_COST_CENTERS B,
                SHR_PAY_CODES D
        WHERE
                A.OEM_ID = :oemId
                AND A.SCHEDULE_DATE >= :startDateSql
                AND A.SCHEDULE_DATE <= :endDateSql
                AND A.SCH_COST_CENTER_ID IN (
                    SELECT NUMERIC_VALUE FROM PC_ARRAY_OF_INTEGERS_GT WHERE ARRAY_KEY = :tempCostCenterIdKey
                )
                AND B.SCH_COST_CENTER_ID(+) = A.SCH_COST_CENTER_ID
                AND D.SHR_PAY_CODE_ID(+) = A.SHR_PAY_CODE_ID
                AND D.COUNT_TOWARD_HOURS = 1
        GROUP BY
                A.ACCOUNT_ID,
                A.SCHEDULE_DATE,
                A.START_DATE,
                A.END_DATE,
                A.SCH_COST_CENTER_ID,
                B.SCH_UNIT_ID_PRIMARY 
        ORDER BY
                A.SCHEDULE_DATE desc
        """

    /**
     * requestDTO contains start date, end date, list of unit ids and mode ["EXCUSE_NEEDED", "VIEW_ALL"]
     * EXCUSE_NEEDED: the scheduled shift has not already been excused and has punches attached
     * VIEW_ALL: all schedules shifts with a set excuse or none, and having punches or not
     *
     * get a list of scheduled shifts based on the Manager's READWRITE units
     * returning a DTO of records that combine
     * schedules: active schedules where the adjustment is direct care in units manager has access to
     * excuses: 4 types of excuses [START_EARLY, START_LATE, END_EARLY, END_LATE] - single schedule can have a combination of these 4 types
     * punches: will return a List<String> of matched punch times based on the census date for the schedule shift
     * assignment: scheduled shift can have multiple assignments per shift - returning the 1st assignment unit area name
     *
     * @param staffManager
     * @param unitIds
     * @param mode
     * @param startDate
     * @param endDate
     * @return
     */
    Collection<ExcuseTimeGridDTO> getExcuseTimeList(User staffManager, List<Long> unitIds, String mode, LocalDate startDate, LocalDate endDate) {
        if (!unitIds) {
            return []
        }
        Collection<StaffManagerUnit> allManagerUnits = getManagerUnits(null, staffManager.id, staffManager.oemId, [CostCenterAccessMode.READWRITE.toString(), CostCenterAccessMode.READONLY.toString()])

        Collection<SchAcctSchedule> scheduleList = getScheduledShifts(allManagerUnits, unitIds, startDate, endDate, staffManager)

        return buildExcuseGridDtos(allManagerUnits, mode, startDate, endDate, staffManager, scheduleList)
    }

    /**
     * Get the active schedules for the staff working in units selected by the user during the date range selected
     * get a unique list of the users working during this time frame and get any scheduled shifts
     * in units that the manager has read or write access for during the same date range
     *
     * @param allManagerUnits
     * @param unitIds
     * @param startDate
     * @param endDate
     * @param staffManager
     * @return
     */
    List<SchAcctSchedule> getScheduledShifts(
            Collection<StaffManagerUnit> allManagerUnits,
            List<Long> unitIds,
            LocalDate startDate,
            LocalDate endDate,
            User staffManager
    ) {
        List<Unit> requestDtoUnits = allManagerUnits?.findAll { unitIds.contains(it.unit.id) }?.unit
        if (!requestDtoUnits) {
            throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_321))
        }

        List<SchAcctSchedule> scheduleList = dailyScheduleService.loadScheduleList(staffManager.oemId, startDate, endDate, requestDtoUnits)
        List<Long> userIds = scheduleList?.user?.id?.unique()
        List<Long> otherUnitIds = allManagerUnits.findAll { !unitIds.contains(it.unit.id) }?.unit?.id?.unique()

        if (otherUnitIds && userIds) {
            scheduleList.addAll(dailyScheduleService.loadScheduleListByUsersAndUnits(staffManager.oemId, startDate, endDate, otherUnitIds, userIds))
        }
        return scheduleList
    }

    /**
     * Loop through the schedules and attach all related punches, excuses and assignments
     *
     * @param allManagerUnits
     * @param mode
     * @param startDate
     * @param endDate
     * @param staffManager
     * @param scheduleList
     * @return
     */
    private Collection<ExcuseTimeGridDTO> buildExcuseGridDtos(
            Collection<StaffManagerUnit> allManagerUnits,
            String mode,
            LocalDate startDate,
            LocalDate endDate,
            User staffManager,
            List<SchAcctSchedule> scheduleList
    ) {
        if (!scheduleList) {
            return []
        }
        List<Unit> managerRWUnits = allManagerUnits.findAll { it.accessMode == CostCenterAccessMode.READWRITE.toString() }?.unit

        Map<SchAcctSchedule, List<Assignment>> assignmentMap = new HashMap<SchAcctSchedule, List<Assignment>>()
        Collection<SchAcctScheduleExcuse> scheduleExcuses = []
        Collection<AccountPunchTimeDTO> punches = []

        Iterables.partition(scheduleList, 999).each { schedules ->
            assignmentMap.putAll(assignmentRepository.findByStaffIn(schedules).groupBy { it.staff })
            scheduleExcuses.addAll(scheduleExcuseRepository.findAllByOemIdAndScheduleIdIn(staffManager.oemId, schedules.id))
            punches.addAll(getPunches(staffManager.oemId, startDate.minusDays(1), endDate, schedules.unit.costCenter.id))
        }

        if (mode == "EXCUSE_NEEDED") {
            scheduleList.removeAll { schedule -> scheduleExcuses.collect { it.scheduleId }.find { it == schedule.id } }
        }

        Collection<ExcuseTimeGridDTO> list = scheduleList.findResults { schedule ->
            buildExcuseTimeGridDtos(
                    isUnitReadOnly(schedule.unit, managerRWUnits),
                    schedule,
                    filterPunches(punches, schedule),
                    scheduleExcuses.findAll { schedule.id == it.scheduleId },
                    assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                    userUtilityService.canStaffManagerViewProfile(staffManager, schedule.user)
            )
        }.flatten() as Collection<ExcuseTimeGridDTO>

        if (mode == "EXCUSE_NEEDED") {
            list.removeAll { it.punches.size() == 0 }
        }

        return list.sort { a, b -> a.startTimeSort <=> b.startTimeSort ?: a.endTimeSort <=> b.endTimeSort ?: a.name <=> b.name ?: b.skillSort <=> a.skillSort }
    }

    /**
     *
     * @param schedule
     * @param managerRWUnits
     * @return
     */
    private static Boolean isUnitReadOnly(Unit unit, List<Unit> managerRWUnits) {
        return managerRWUnits.empty || !managerRWUnits.contains(unit)
    }

    /**
     * returns the punches for a list of cost centers based on unit ids and date range
     *
     * @param oemId
     * @param startDate
     * @param endDate
     * @param costCenterIdList
     * @return
     */
    List<AccountPunchTimeDTO> getPunches(
            Short oemId,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> costCenterIdList) {
        Date startDateSql = Date.valueOf(startDate)
        Date endDateSql = Date.valueOf(endDate)
        MapSqlParameterSource parameters = new MapSqlParameterSource()
        parameters.addValue("oemId", oemId)
        parameters.addValue("startDateSql", startDateSql)
        parameters.addValue("endDateSql", endDateSql)
        parameters.addValue("tempCostCenterIdKey", utilsRepository.insertTempIds(costCenterIdList))
        return jdbcTemplate.query(punchDataSql, parameters, new AccountPunchTimeRowMapper())
    }

    /**
     * filter punches for scheduled shift
     * @param punches
     * @param schedule
     * @return
     */
    static List<AccountPunchTimeDTO> filterPunches(Collection<AccountPunchTimeDTO> punches, SchAcctSchedule schedule) {
        punches.findAll {
            schedule.user.id == it.accountId && it.punchEnd >= schedule.fullStartLocalDateTime.minusHours(12) && it.punchStart <= schedule.fullEndLocalDateTime.plusHours(12)
        }.toList()
    }

    /**
     * Collect and Build the return DTO for the grid
     *
     * @param schedule
     * @param punchDtos
     * @param scheduleExcuseList
     * @param areaName
     * @return
     */
    static List<ExcuseTimeGridDTO> buildExcuseTimeGridDtos(
            Boolean isReadOnly,
            SchAcctSchedule schedule,
            Collection<AccountPunchTimeDTO> punchDtos,
            Collection<SchAcctScheduleExcuse> scheduleExcuseList,
            String areaName,
            Boolean showProfileLink) {
        return scheduleExcuseList.isEmpty() ?
                [new ExcuseTimeGridDTO(isReadOnly, schedule, punchDtos, null, areaName, showProfileLink)] :
                scheduleExcuseList.collect { scheduleExcuse ->
                    new ExcuseTimeGridDTO(isReadOnly, schedule, punchDtos, scheduleExcuse, areaName, showProfileLink)
                }
    }

    /**
     * save a single excuse from the manage excuse time list
     *
     * @param excuseToSave
     * @param manager
     * @return
     */
    ScheduleExcuseDTO saveExcuse(ScheduleExcuseDTO excuseToSave, User manager) {

        Short oemId = manager.oemId
        Long scheduleId = excuseToSave.scheduleId
        SchAcctSchedule schedule = getSchedule(scheduleId, oemId)

        hasUnitReadWriteAccess(oemId, manager.id, schedule.unit.id, schedule.unit.unitName, scheduleId, excuseToSave.scheduleExcuseId, IllegalArgumentException)

        // if type and status are null delete the excuse and return the schedule id
        if (!excuseToSave.excuseType && !excuseToSave.excuseStatus && excuseToSave.scheduleExcuseId) {
            SchAcctScheduleExcuse excuse = getScheduleExcuse(excuseToSave.scheduleExcuseId, oemId)
            scheduleExcuseRepository.delete(excuse.id)
            return new ScheduleExcuseDTO(scheduleId: excuseToSave.scheduleId)
        }

        ScheduleExcuseType excuseType = scheduleExcuseTypeRepository.findByKey(ScheduleExcuseTypeKeys.valueOf(excuseToSave.excuseType))
        List<SchAcctScheduleExcuse> scheduleExcuses = scheduleExcuseRepository.findAllByOemIdAndScheduleId(oemId, scheduleId)
        Long excuseId = scheduleExcuses.find { it.excuseType == excuseType }?.id ?: excuseToSave.scheduleExcuseId
        if (excuseId) {
            return saveExistingExcuse(oemId, excuseId, excuseToSave, excuseType)
        }
        SchAcctScheduleExcuse excuse = new SchAcctScheduleExcuse(
                oemId: oemId,
                user: schedule.user,
                scheduleId: scheduleId,
                excuseType: excuseType,
                status: ScheduleExcuseStatus.valueOf(excuseToSave.excuseStatus),
                hasIncentive: false,
                modUser: manager
        )
        SchAcctScheduleExcuse saved = scheduleExcuseRepository.save(excuse)

        return new ScheduleExcuseDTO(saved)
    }

    /**
     * Save a single excuse and return the new schedule excuse id for updating the grid
     *
     * @param excuseId
     * @param oemId
     * @param excuseToSave
     * @param excuseType
     * @return
     */
    private ScheduleExcuseDTO saveExistingExcuse(Short oemId, Long scheduleExcuseId, ScheduleExcuseDTO excuseToSave, ScheduleExcuseType excuseType) {
        SchAcctScheduleExcuse excuse = getScheduleExcuse(scheduleExcuseId, oemId)
        SchAcctScheduleExcuse finalExcuse = excuse
        if (excuse.excuseType.key.toString() != excuseToSave.excuseType || excuse.status.toString() != excuseToSave.excuseStatus || excuse.scheduleId != excuseToSave.scheduleId || excuse?.payRule?.id != excuseToSave.incentiveId) {
            excuse.scheduleId = excuseToSave.scheduleId
            excuse.excuseType = excuseType
            excuse.status = ScheduleExcuseStatus.valueOf(excuseToSave.excuseStatus)
            excuse.payRule = excuseToSave.incentiveId ? payRuleRepository.findByIdAndOemId(excuseToSave.incentiveId, oemId) : null
            finalExcuse = scheduleExcuseRepository.save(excuse)
        }

        return new ScheduleExcuseDTO(finalExcuse)
    }

    /**
     * Save a single note attached to schedule excuse
     *
     * @param excuseNote
     * @param manager
     * @return
     */
    ScheduleExcuseDTO saveSingleNote(ScheduleExcuseDTO excuseNote, User manager) {
        return saveNote(null, excuseNote.scheduleExcuseId, manager, excuseNote.note, 'EXCEPT')
    }

    /**
     *
     * @param scheduleId
     * @param excuseId
     * @param manager
     * @param note
     * @return
     */
    private ScheduleExcuseDTO saveNote(SchAcctSchedule currentSchedule, Long excuseId, User manager, String note, String type) {
        Short oemId = manager.oemId
        SchAcctScheduleExcuse excuse = getScheduleExcuse(excuseId, oemId)
        SchAcctSchedule schedule = currentSchedule ?: getSchedule(excuse.scheduleId, oemId)
        hasUnitReadWriteAccess(oemId, manager.id, schedule.unit.id, schedule.unit.unitName, excuse.scheduleId, excuseId, type == 'BULK' ? SaveScheduleExcusesException : IllegalArgumentException)

        if (excuse.note != note) {
            excuse.note = note
            excuse.modUser = manager
            SchAcctScheduleExcuse saved = scheduleExcuseRepository.save(excuse)
            return new ScheduleExcuseDTO(saved)
        }

        return new ScheduleExcuseDTO(excuse)
    }

    /**
     * Save bulk excuse from the manage excuse time list and return updates.
     * If Shift has Matching Excuse Type
     * --If Shift Excuse is Checked
     * ----If Bulk Edit Excuse Status Set
     * ------Update Excuse
     * ----Else
     * ------Delete Excuse
     * --Else
     * ----Do Nothing
     * Else
     * --If Bulk Edit Excuse Status Set
     * ----Add Excuse
     * --Else
     * ----Do Nothing
     *
     * If no schedules are provided, do nothing.
     *
     * If too many excuses of one type are provided, throw an error
     *
     * If no excuse configs are provided, be sure to still update the note.
     *
     * Individual Record Errors:
     * --Schedule ID does not match record in DB
     * --Excuse ID does not match record in DB
     * --Combination of Schedule ID and Excuse ID does not match record in DB
     *
     * @param requestDto
     * @param manager
     * @return
     */
    List<BulkEditResponseDTO> saveExcuses(BulkEditRequestDTO requestDto, User manager) {

        if (!requestDto.scheduleExcuses) {
            throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_320))
        }

        Map<ScheduleExcuseType, ScheduleExcuseStatus> excuseTypeMap = [:]
        if (requestDto.excuseConfigs) {
            validateExcuses(requestDto.excuseConfigs)
            excuseTypeMap = getExcuseTypeMap(requestDto.excuseConfigs)
        } else {
            excuseTypeMap.put(new ScheduleExcuseType(), null)
        }

        List<Long> scheduleIds = requestDto.scheduleExcuses.scheduleId.unique()
        List<Long> excuseIds = requestDto.scheduleExcuses.scheduleExcuseId.unique()
        List<Long> incentiveIds = requestDto.excuseConfigs?.incentiveId?.unique() ?: []
        excuseIds.remove(null)
        incentiveIds.remove(null)

        String tempSchAcctScheduleIdKey = utilsRepository.insertTempIds(scheduleIds)
        List<SchAcctSchedule> schedules = scheduleRepository.findByOemIdAndIdIn(manager.oemId, tempSchAcctScheduleIdKey)

        List<SchAcctScheduleExcuse> existingExcuses = []
        Iterables.partition(scheduleIds, 999).each { scheduleIdList ->
            existingExcuses.addAll(scheduleExcuseRepository.findAllByOemIdAndScheduleIdIn(manager.oemId, scheduleIdList))
        }

        List<PayRule> payRules = []
        if (incentiveIds) {
            String tempPayRuleIdKey = utilsRepository.insertTempIds(incentiveIds)
            payRules = payRuleRepository.findAllByOemIdAndIdIn(manager.oemId, tempPayRuleIdKey)
        }

        List<BulkEditResponseDTO> response = populateErrorRecords(requestDto.scheduleExcuses, schedules, existingExcuses)

        Map<SchAcctSchedule, List<SchAcctScheduleExcuse>> excuseMap =
                existingExcuses.groupBy { scheduleExcuse ->
                    schedules.find { it.id == scheduleExcuse.scheduleId }
                }

        response.addAll(manageExcuses(requestDto, excuseTypeMap, excuseMap, excuseIds, schedules, payRules, manager))

        return response
    }

    /**
     * manageExcuses
     *
     * process returns records for manage excuse time bulk editing
     * note:  client code will remove all records attached to schedule ids attached to the excuses in the request
     * ignoreExcuses will contain all excuses that are not in the update or delete arrays and the are passed back in the API to client
     *
     * @param requestDto
     * @param excuseTypeMap
     * @param excuseMap
     * @param excuseIds
     * @param schedules
     * @param payRules
     * @param manager
     * @return
     */
    private List<BulkEditResponseDTO> manageExcuses(
            BulkEditRequestDTO requestDto,
            Map<ScheduleExcuseType, ScheduleExcuseStatus> excuseTypeMap,
            Map<SchAcctSchedule, List<SchAcctScheduleExcuse>> excuseMap,
            List<Long> excuseIds,
            List<SchAcctSchedule> schedules,
            List<PayRule> payRules,
            User manager
    ) {
        List<BulkEditResponseDTO> response = []
        List<SchAcctScheduleExcuse> updateExcuses = []
        List<SchAcctScheduleExcuse> deleteExcuses = []
        List<SchAcctScheduleExcuse> ignoreExcuses = []
        for (Map.Entry<ScheduleExcuseType, ScheduleExcuseStatus> entry : excuseTypeMap.entrySet()) {
            for (SchAcctSchedule schedule : schedules) {
                List<List<SchAcctScheduleExcuse>> results =
                        buildExcusesMap(
                                requestDto,
                                schedule,
                                excuseMap,
                                excuseIds,
                                entry.key,
                                entry.value,
                                payRules,
                                manager
                        )
                updateExcuses.addAll(results[0])
                deleteExcuses.addAll(results[1])
                ignoreExcuses.addAll(results[2])
            }
        }

        ignoreExcuses = ignoreExcuses.unique().findAll { !updateExcuses.contains(it) && !deleteExcuses.contains(it) }

        String tempSchAcctScheduleIdKey = utilsRepository.insertTempIds([updateExcuses.scheduleId, deleteExcuses.scheduleId].flatten().unique() as List<Long>)
        Map<Long, SchAcctSchedule> schedulesMap =
                scheduleRepository.findByOemIdAndIdIn(manager.oemId, tempSchAcctScheduleIdKey).collectEntries { [it.id, it] }
        Map<SchAcctSchedule, List<Assignment>> assignmentMap = assignmentRepository.findByStaffIn(schedules).groupBy { it.staff }

        List<Unit> managerRWUnits = getManagerUnits(null, manager.id, manager.oemId, [CostCenterAccessMode.READWRITE.toString()])?.unit
        List<AccountPunchTimeDTO> punches = schedules ? getPunches(manager.oemId, schedules.scheduleLocalDate.min().minusDays(1), schedules.scheduleLocalDate.max(), schedules.unit.costCenter.id) : []

        if (updateExcuses) {
            List<SchAcctScheduleExcuse> excusesToAdd = updateExcuses.findAll { !it.id }
            List<SchAcctScheduleExcuse> excusesToUpdate = updateExcuses.findAll { it.id }
            if (excusesToAdd) {
                excusesToAdd = scheduleExcuseRepository.save(excusesToAdd)
                response.addAll(excusesToAdd.findResults {
                    SchAcctSchedule schedule = schedulesMap.get(it.scheduleId)
                    new BulkEditResponseDTO(
                            it,
                            schedule,
                            filterPunches(punches, schedule),
                            isUnitReadOnly(schedule.unit, managerRWUnits),
                            userUtilityService.canStaffManagerViewProfile(manager, schedule.user),
                            assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                            "",
                            ManageExcuseStatus.ADDED
                    )
                })
            }

            if (excusesToUpdate) {
                excusesToUpdate = scheduleExcuseRepository.save(excusesToUpdate)
                response.addAll(excusesToUpdate.findResults {
                    SchAcctSchedule schedule = schedulesMap.get(it.scheduleId)
                    new BulkEditResponseDTO(
                            it,
                            schedule,
                            filterPunches(punches, schedule),
                            isUnitReadOnly(schedule.unit, managerRWUnits),
                            userUtilityService.canStaffManagerViewProfile(manager, schedule.user),
                            assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                            "",
                            ManageExcuseStatus.UPDATED
                    )
                })
            }
        }

        // pass back the ignored, because we are clearing all excuses in the list for this schedule id
        if (ignoreExcuses) {
            response.addAll(ignoreExcuses.findResults {
                SchAcctSchedule schedule = schedulesMap.get(it.scheduleId)
                new BulkEditResponseDTO(
                        it,
                        schedule,
                        filterPunches(punches, schedule),
                        isUnitReadOnly(schedule.unit, managerRWUnits),
                        userUtilityService.canStaffManagerViewProfile(manager, schedule.user),
                        assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                        "",
                        ManageExcuseStatus.IGNORED
                )
            })
        }

        if (deleteExcuses) {
            scheduleExcuseRepository.deleteInBatch(deleteExcuses)
            response.addAll(deleteExcuses.findResults {
                SchAcctSchedule schedule = schedulesMap.get(it.scheduleId)
                new BulkEditResponseDTO(
                        it,
                        schedule,
                        filterPunches(punches, schedule),
                        isUnitReadOnly(schedule.unit, managerRWUnits),
                        userUtilityService.canStaffManagerViewProfile(manager, schedule.user),
                        assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                        "",
                        ManageExcuseStatus.DELETED
                )
            })

            //adding back the original schedule item for any schedules without excuses for the manage excuse time list
            def scheduleIds = []
            response.addAll(deleteExcuses.findResults {
                def excuse = scheduleExcuseRepository.findAllByOemIdAndScheduleId(it.oemId, it.scheduleId)
                if (!excuse && !scheduleIds.contains(it.scheduleId)) {
                    SchAcctSchedule schedule = schedulesMap.get(it.scheduleId)
                    scheduleIds.add(it.scheduleId)
                    new BulkEditResponseDTO(
                            null,
                            schedule,
                            filterPunches(punches, schedule),
                            isUnitReadOnly(schedule.unit, managerRWUnits),
                            userUtilityService.canStaffManagerViewProfile(manager, schedule.user),
                            assignmentMap?.get(schedule)?.get(0)?.area?.unitArea?.areaName,
                            "",
                            ManageExcuseStatus.ADDED
                    )
                }
            })
        }

        return response
    }

    private static List<List<SchAcctScheduleExcuse>> buildExcusesMap(
            BulkEditRequestDTO requestDto,
            SchAcctSchedule schedule,
            Map<SchAcctSchedule, List<SchAcctScheduleExcuse>> excuseMap,
            List<Long> excuseIds,
            ScheduleExcuseType excuseType,
            ScheduleExcuseStatus excuseStatus,
            List<PayRule> payRules,
            User manager
    ) {
        List<SchAcctScheduleExcuse> updateExcuses = []
        List<SchAcctScheduleExcuse> deleteExcuses = []
        List<SchAcctScheduleExcuse> ignoreExcuses = []
        List<SchAcctScheduleExcuse> existingExcuses =
                excuseMap.get(schedule).findAll { !excuseType.id || it.excuseType == excuseType }
        if (existingExcuses) {
            for (SchAcctScheduleExcuse existingExcuse : existingExcuses) {
                if (excuseMap.get(schedule).findAll { excuseIds.contains(it.id) }.contains(existingExcuse)) {
                    if (!excuseType.id || excuseStatus) {
                        existingExcuse.status = excuseStatus ?: existingExcuse.status
                        existingExcuse.note = requestDto.excuseNote
                        existingExcuse.payRule = getExcusePayRule(
                                payRules, requestDto.excuseConfigs, excuseType, excuseStatus)
                        existingExcuse.modUser = manager
                        updateExcuses.add(existingExcuse)
                    } else {
                        deleteExcuses.add(existingExcuse)
                    }
                }
            }
        } else {
            if (excuseType && excuseStatus) {
                updateExcuses.add(
                        buildNewExcuse(
                                schedule, excuseType, excuseStatus, requestDto.excuseNote,
                                getExcusePayRule(payRules, requestDto.excuseConfigs, excuseType, excuseStatus),
                                manager
                        )
                )
            }
        }

        ignoreExcuses.addAll(excuseMap.get(schedule).findAll { !existingExcuses.contains(it) })

        return [updateExcuses, deleteExcuses, ignoreExcuses]
    }

    private static PayRule getExcusePayRule(
            List<PayRule> payRules,
            List<ExcuseConfigDTO> excuseConfigs,
            ScheduleExcuseType excuseType,
            ScheduleExcuseStatus excuseStatus
    ) {
        PayRule payRule = payRules.find { payRule ->
            payRule.id == excuseConfigs.find {
                it.excuseType == excuseType.key.toString() && it.excuseStatus == excuseStatus?.toString()
            }.incentiveId
        }
        payRule
    }

    static void validateExcuses(List<ExcuseConfigDTO> excuseConfigs) {
        Map<ScheduleExcuseTypeKeys, List<ExcuseConfigDTO>> excuseTypeDebugMap = excuseConfigs.groupBy {
            ScheduleExcuseTypeKeys.valueOf(it.excuseType)
        }
        excuseTypeDebugMap.each { type, configs ->
            int excuseTypeListSize = configs.size()
            if (excuseTypeListSize > 1) {
                throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_317, type.toString(), excuseTypeListSize.toString()))
            }

            switch (type) {
                case ScheduleExcuseTypeKeys.START_EARLY:
                case ScheduleExcuseTypeKeys.END_LATE:
                    String status = configs[0].excuseStatus
                    if (configs[0].incentiveId != null &&
                            ((status && ScheduleExcuseStatus.valueOf(status) == ScheduleExcuseStatus.UNEXCUSED) || status == null)
                    ) {
                        throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_319))
                    }
                    break
                default:
                    if (configs[0].incentiveId != null) {
                        throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_319))
                    }
                    break
            }
        }
    }

    static List<BulkEditResponseDTO> populateErrorRecords(
            List<BulkExcuseIdsDTO> scheduleExcuseDtos,
            List<SchAcctSchedule> schedules,
            List<SchAcctScheduleExcuse> existingExcuses
    ) {
        List<BulkEditResponseDTO> response = []
        for (BulkExcuseIdsDTO scheduleExcuse : scheduleExcuseDtos) {
            Long scheduleId = scheduleExcuse.scheduleId
            Long excuseId = scheduleExcuse.scheduleExcuseId

            String message = ""
            if (!schedules.id.contains(scheduleId)) {
                message += ErrorCodes.getComplete(ErrorCodes.ERRORCODE_300, scheduleId.toString())
            }
            if (excuseId) {
                if (!existingExcuses.id.contains(excuseId)) {
                    message += ErrorCodes.getComplete(ErrorCodes.ERRORCODE_316, excuseId.toString())
                } else if (existingExcuses.find { it.id == excuseId }.scheduleId != scheduleId) {
                    message += ErrorCodes.getComplete(ErrorCodes.ERRORCODE_318, excuseId.toString(), scheduleId.toString())
                }
            }

            if (message) {
                log.error(message)
                response.add(new BulkEditResponseDTO(scheduleId, excuseId, ManageExcuseStatus.FAILED, message))
            }
        }
        return response
    }

    private Map<ScheduleExcuseType, ScheduleExcuseStatus> getExcuseTypeMap(List<ExcuseConfigDTO> excuseConfigs) {
        List<ScheduleExcuseType> scheduleExcuseTypes =
                scheduleExcuseTypeRepository.findByKeyIn(excuseConfigs.excuseType.collect {
                    ScheduleExcuseTypeKeys.valueOf(it)
                })

        return excuseConfigs.collectEntries { config ->
            [
                    scheduleExcuseTypes.find { it.key == ScheduleExcuseTypeKeys.valueOf(config.excuseType) },
                    config.excuseStatus ? ScheduleExcuseStatus.valueOf(config.excuseStatus) : null
            ]
        }
    }

    /**
     * Return the status of the  bulk edit when it is a new insert to the Schedule excuse table
     *
     * @param schedule
     * @param scheduleExcuseType
     * @param bulkEditRequestDTO
     * @param manager
     * @return
     */
    private static SchAcctScheduleExcuse buildNewExcuse(
            SchAcctSchedule schedule,
            ScheduleExcuseType scheduleExcuseType,
            ScheduleExcuseStatus excuseStatus,
            String excuseNote,
            PayRule payRule,
            User manager
    ) {
        return new SchAcctScheduleExcuse(
                oemId: schedule.oemId,
                user: schedule.user,
                scheduleId: schedule.id,
                excuseType: scheduleExcuseType,
                status: excuseStatus,
                hasIncentive: false,
                modUser: manager,
                note: excuseNote,
                payRule: payRule
        )
    }

    /**
     * Return all the units that the manager access to based on the input unit ids and access modes
     *
     * @param unitIds null will return all units for this manager based on the access mode given
     * @param managerId
     * @param oemId
     * @return
     */
    Collection<StaffManagerUnit> getManagerUnits(List<Long> unitIds, Long managerId, Short oemId, List<String> accessModes) {
        if (unitIds) {
            Collection<StaffManagerUnit> managerUnits = staffManagerUnitRepository.findAllByOemIdAndAccountIdAndUnitIdInAndActiveTrueAndStartLocalDateLessThanEqualAndEndLocalDateGreaterThanEqualAndAccessModeIn(oemId, managerId, unitIds, LocalDate.now(), LocalDate.now(), accessModes)
            if (!managerUnits) {
                return []
            }
            return managerUnits
        } else {
            return staffManagerUnitRepository.findAllByOemIdAndAccountIdAndActiveTrueAndStartLocalDateLessThanEqualAndEndLocalDateGreaterThanEqualAndAccessModeIn(oemId, managerId, LocalDate.now(), LocalDate.now(), accessModes)
        }
    }

    /**
     * validating the manager has READWRITE access to the unit
     * use this to check before saving an excuse or saving a excuse note
     *
     * @param oemId
     * @param managerId
     * @param unitId
     * @param unitName
     * @return
     */
    void hasUnitReadWriteAccess(Short oemId, Long managerId, Long unitId, String unitName, Long scheduleId, Long scheduleExcuseId, Class<?> cls = IllegalArgumentException) {
        Collection<StaffManagerUnit> managerUnits = staffManagerUnitRepository.findAllByOemIdAndAccountIdAndUnitIdInAndActiveTrueAndStartLocalDateLessThanEqualAndEndLocalDateGreaterThanEqualAndAccessModeIn(oemId, managerId, [unitId], LocalDate.now(), LocalDate.now(), [CostCenterAccessMode.READWRITE.toString()])
        if (!managerUnits) {
            if (cls == SaveScheduleExcusesException.class) {
                BulkExcuseIdsDTO scheduleExcusesDTO = new BulkExcuseIdsDTO(scheduleId: scheduleId, scheduleExcuseId: scheduleExcuseId)
                throw new SaveScheduleExcusesException(scheduleExcusesDTO, "FAILED", ErrorCodes.getComplete(ErrorCodes.ERRORCODE_315, unitName))
            } else {
                throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_315, unitName))
            }

        }
    }

    /**
     *
     * @param excuseId
     * @param oemId
     * @param scheduleExcuseId
     * @return
     */
    SchAcctScheduleExcuse getScheduleExcuse(Long excuseId, Short oemId) {
        SchAcctScheduleExcuse excuse = scheduleExcuseRepository.findByIdAndOemId(excuseId, oemId)
        if (!excuse) {
            throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_316, excuseId.toString()))
        }
        return excuse
    }

    SchAcctSchedule getSchedule(Long id, Short oemId) {
        SchAcctSchedule schedule = scheduleRepository.findByIdAndOemId(id, oemId)
        if (!schedule) {
            throw new IllegalArgumentException(ErrorCodes.getComplete(ErrorCodes.ERRORCODE_300, id.toString()))
        }
        return schedule
    }

}