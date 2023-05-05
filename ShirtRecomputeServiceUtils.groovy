package com.avantas.pop.api.service

import static com.avantas.pop.api.enums.ScheduleRecordDateSignificance.FIRST_DAY_OF_NEXT_PERIOD
import static com.avantas.pop.api.enums.ScheduleRecordDateSignificance.FIRST_DAY_OF_WEEK
import static com.avantas.pop.api.enums.ScheduleRecordDateSignificance.LAST_DAY_OF_PREVIOUS_PERIOD
import static com.avantas.pop.api.enums.ScheduleRecordDateSignificance.LAST_DAY_OF_WEEK
import static com.avantas.pop.api.enums.ScheduleRecordDateSignificance.NONE
import static com.avantas.pop.api.enums.ShiftRecomputeTypeToClear.CLEAR_ALL_EXTRA_AND_OT
import static com.avantas.pop.api.enums.ShiftRecomputeTypeToClear.EXTRA_AND_OT_BY_WEEK
import static com.avantas.pop.api.enums.ShiftRecomputeTypeToClear.EXTRA_ONLY
import static com.avantas.pop.api.enums.ShiftRecomputeTypeToClear.OVERTIME_ONLY

import com.avantas.pop.api.dto.recompute.CostCenterRecordDTO
import com.avantas.pop.api.dto.recompute.OvertimeRecordDTO
import com.avantas.pop.api.dto.recompute.ScheduleRecordDTO
import com.avantas.pop.api.enums.ShiftRecomputeTypeToClear
import com.avantas.smartsquare.entity.overtime.OvertimeRule
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service
import org.threeten.extra.Hours
import org.threeten.extra.Minutes

import java.time.LocalDate
import java.time.LocalDateTime

@Slf4j
@Service
class ShiftRecomputeServiceUtils {

    /**
     * SCH_SHIFT_RECOMPUTE.RECOMPUTE_EXTRA_HRS
     *
     * @param hoursToMark           - L_EXTRA_HOURS_TO_MARK
     * @param hoursToMarkOriginal   - L_EXTRA_HOURS_TO_MARK_SAVE
     * @param normalAccountingId    - L_NORMAL_SCH_ACCOUNTING_ID
     * @param extraAccountingId     - L_EXTRA_SCH_ACCOUNTING_ID
     * @param scheduleRecordList    - L_SCH_RECS
     * @return
     */
    static void recomputeExtraHours(
            BigDecimal hoursToMark,
            BigDecimal hoursToMarkOriginal,
            long normalAccountingId,
            long extraAccountingId,
            List<ScheduleRecordDTO> scheduleRecordList
    ) {
        for (ScheduleRecordDTO scheduleRecord : scheduleRecordList) {
            if (scheduleRecord.adjustUpToFte && scheduleRecord.accountingId != normalAccountingId) {
                scheduleRecord.with {
                    accountingId = normalAccountingId
                    isModified = true
                    workingDecision = 'UNKNOWN'
                    extraHours = 0
                    curDayExtraHours = 0
                    nextDayExtraHours = 0
                    prevDayExtraHours = 0
                    overtimeHours = 0
                    curDayOvertimeHours = 0
                    nextDayOvertimeHours = 0
                    prevDayOvertimeHours = 0
                }
            } else {
                scheduleRecord.extraHours = getTotalExtraHours(scheduleRecord)
            }
        }

        BigDecimal hoursToMarkLocal = hoursToMark
        BigDecimal totalExtraHours = scheduleRecordList.extraHours.sum() as BigDecimal

        if (totalExtraHours > hoursToMarkOriginal) {
            for (ScheduleRecordDTO scheduleRecord : scheduleRecordList.reverse()) {
                if (scheduleRecord.extraHours > 0) {
                    BigDecimal extraHoursRemaining = [hoursToMarkLocal, scheduleRecord.extraHours].min()
                    scheduleRecord.isModified = true
                    scheduleRecord.extraHours -= extraHoursRemaining
                    hoursToMarkLocal -= extraHoursRemaining

                    if (scheduleRecord.extraHours == 0 && scheduleRecord.accountingId != extraAccountingId) {
                        scheduleRecord.accountingId = normalAccountingId
                    }
                }
                if (hoursToMarkLocal <= 0) {
                    break
                }
            }
        }
    }

    /**
     * SCH_SHIFT_RECOMPUTE.DETERMINE_DURATION_HOURS
     *
     * @param fteExtraOvertimeDateAssignMethod      - L_SCH_FTE_EXOT_DATE_ASGN
     * @param fteExtraOvertimeSplitHour             - L_SCH_FTE_EXOT_SPLIT_HOUR
     * @param scheduleRecord                        - L_SCH_RECS(L_SCH_LOOP_CTR)
     * @return
     */
    static ScheduleRecordDTO determineDurationHours(
            String fteExtraOvertimeDateAssignMethod,
            long fteExtraOvertimeSplitHour,
            ScheduleRecordDTO scheduleRecord
    ) {
        scheduleRecord.prevDayDurationHours = 0
        scheduleRecord.nextDayDurationHours = 0

        LocalDateTime startOfEndDate = scheduleRecord.fullEndDateTime.toLocalDate().atStartOfDay()
        switch(fteExtraOvertimeDateAssignMethod) {
            case 'START_DATE_OF_SHIFT':
                scheduleRecord.with {
                    prevDayExtraHours = 0
                    prevDayOvertimeHours = 0
                    prevDayFteHours = 0
                    nextDayExtraHours = 0
                    nextDayOvertimeHours = 0
                    nextDayFteHours = 0
                    curDayExtraHours = scheduleRecord.extraHours
                    curDayOvertimeHours = scheduleRecord.overtimeHours
                    curDayFteHours = scheduleRecord.fteHours
                }
                break
            case 'END_DATE_OF_SHIFT':
                // if the end date is greater than the schedule date, assign all hours to "NEXT DAY"
                if (scheduleRecord.fullEndDateTime.toLocalDate() > scheduleRecord.scheduleDate) {
                    scheduleRecord.with {
                        nextDayDurationHours = scheduleRecord.durationHours
                        prevDayExtraHours = 0
                        prevDayOvertimeHours = 0
                        prevDayFteHours = 0
                    }
                } else {
                    scheduleRecord.with {
                        prevDayExtraHours = 0
                        prevDayOvertimeHours = 0
                        prevDayFteHours = 0
                        nextDayExtraHours = 0
                        nextDayOvertimeHours = 0
                        nextDayFteHours = 0
                    }
                }
                break

            case 'DATE_OF_MAJORITY_HRS_START':
                if (scheduleRecord.fullEndDateTime.toLocalDate() > scheduleRecord.scheduleDate &&
                        Minutes.between(startOfEndDate, scheduleRecord.fullEndDateTime)
                        > Minutes.between(scheduleRecord.fullStartDateTime, startOfEndDate)
                ) {
                    // Ends day after start and more hours are after midnight, assign all hours to "NEXT DAY"
                    scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                } else {
                    // Assign all hours as normal schedule date (normal)
                    scheduleRecord.nextDayExtraHours = 0
                    scheduleRecord.nextDayOvertimeHours = 0
                    scheduleRecord.nextDayFteHours = 0
                }
                scheduleRecord.prevDayExtraHours = 0
                scheduleRecord.prevDayOvertimeHours = 0
                scheduleRecord.prevDayFteHours = 0
                break
            case 'DATE_OF_MAJORITY_HRS_END':
                if (scheduleRecord.fullEndDateTime.toLocalDate() > scheduleRecord.scheduleDate &&
                        Minutes.between(startOfEndDate, scheduleRecord.fullEndDateTime)
                        >= Minutes.between(scheduleRecord.fullStartDateTime, startOfEndDate)
                ) {
                    // Ends day after start and more hours are after midnight OR A TIE, assign all hours to "NEXT DAY"
                    scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                } else {
                    scheduleRecord.nextDayExtraHours = 0
                    scheduleRecord.nextDayOvertimeHours = 0
                    scheduleRecord.nextDayFteHours = 0
                }
                scheduleRecord.prevDayExtraHours = 0
                scheduleRecord.prevDayOvertimeHours = 0
                scheduleRecord.prevDayFteHours = 0
                break
            case 'SPLIT_HOURS_BY_DATE_EOD':
                // compute the split date/time
                boolean splitFound = false
                LocalDateTime fteExtraOvertimeSplitDate = scheduleRecord.scheduleDate.atStartOfDay().plusHours(fteExtraOvertimeSplitHour)

                if ((scheduleRecord.fullEndDateTime > fteExtraOvertimeSplitDate &&
                        scheduleRecord.fullStartDateTime < fteExtraOvertimeSplitDate)) {
                    splitFound = true
                } else {
                    fteExtraOvertimeSplitDate = scheduleRecord.scheduleDate.atStartOfDay().plusHours(fteExtraOvertimeSplitHour).minusDays(1)
                    if ((scheduleRecord.fullEndDateTime > fteExtraOvertimeSplitDate &&
                            scheduleRecord.fullStartDateTime < fteExtraOvertimeSplitDate)) {
                        splitFound = true
                    } else {
                        fteExtraOvertimeSplitDate = scheduleRecord.scheduleDate.atStartOfDay().plusHours(fteExtraOvertimeSplitHour).plusDays(1)
                        if ((scheduleRecord.fullEndDateTime > fteExtraOvertimeSplitDate &&
                                scheduleRecord.fullStartDateTime < fteExtraOvertimeSplitDate)) {
                            splitFound = true
                        }
                    }
                }

                boolean shiftEndsAtMidnight =
                        scheduleRecord.fullEndDateTime == scheduleRecord.fullEndDateTime.toLocalDate().atStartOfDay()

                if (splitFound) {
                    // there are hours on both sides of the split date/time
                    if (scheduleRecord.scheduleDate == fteExtraOvertimeSplitDate.minusDays(1).toLocalDate()) {
                        scheduleRecord.nextDayDurationHours = Hours.between(fteExtraOvertimeSplitDate, scheduleRecord.fullEndDateTime).amount
                        scheduleRecord.prevDayExtraHours = 0
                        scheduleRecord.prevDayOvertimeHours = 0
                        scheduleRecord.prevDayFteHours = 0
                    } else if ((scheduleRecord.fullStartDateTime.toLocalDate() == scheduleRecord.fullEndDateTime.toLocalDate() ||
                            (scheduleRecord.fullStartDateTime.toLocalDate() == scheduleRecord.fullEndDateTime.toLocalDate().minusDays(1) &&
                                    shiftEndsAtMidnight
                            ))
                    ) {
                        // start/end on same date
                        if (fteExtraOvertimeSplitHour <= 24) {
                            //split before midnight
                            scheduleRecord.nextDayDurationHours = Hours.between(fteExtraOvertimeSplitDate, scheduleRecord.fullEndDateTime).amount
                            scheduleRecord.prevDayExtraHours = 0
                            scheduleRecord.prevDayOvertimeHours = 0
                            scheduleRecord.prevDayFteHours = 0
                        } else {
                            //TODO: Dead code? fteExtraOvertimeSplitHour >= 24 results in next-day split, which hits previous if block
                            // split time after midnight
                            scheduleRecord.prevDayDurationHours = Hours.between(scheduleRecord.fullStartDateTime, fteExtraOvertimeSplitDate).amount
                            scheduleRecord.nextDayExtraHours = 0
                            scheduleRecord.nextDayOvertimeHours = 0
                            scheduleRecord.nextDayFteHours = 0
                        }
                    } else {
                        if (fteExtraOvertimeSplitHour < 24) {
                            if (scheduleRecord.scheduleDate == fteExtraOvertimeSplitDate.toLocalDate()) {
                                scheduleRecord.nextDayDurationHours = Hours.between(fteExtraOvertimeSplitDate, scheduleRecord.fullEndDateTime).amount
                                scheduleRecord.prevDayExtraHours = 0
                                scheduleRecord.prevDayOvertimeHours = 0
                                scheduleRecord.prevDayFteHours = 0
                            } else {
                                //TODO: Dead code? fteExtraOvertimeSplitHour >= 24 results in next-day split, which hits previous if block
                                scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                            }
                        } else {
                            //TODO: Dead code? fteExtraOvertimeSplitHour >= 24 results in next-day split, which hits previous if block
                            // split time after midnight
                            if (scheduleRecord.scheduleDate < fteExtraOvertimeSplitDate.toLocalDate()) {
                                scheduleRecord.nextDayDurationHours = Hours.between(fteExtraOvertimeSplitDate, scheduleRecord.fullEndDateTime).amount
                                scheduleRecord.prevDayExtraHours = 0
                                scheduleRecord.prevDayOvertimeHours = 0
                                scheduleRecord.prevDayFteHours = 0
                            } else {
                                scheduleRecord.prevDayDurationHours = Hours.between(scheduleRecord.fullStartDateTime, fteExtraOvertimeSplitDate).amount
                                scheduleRecord.nextDayExtraHours = 0
                                scheduleRecord.nextDayOvertimeHours = 0
                                scheduleRecord.nextDayFteHours = 0
                            }
                        }
                    }
                } else {
                    fteExtraOvertimeSplitDate = scheduleRecord.scheduleDate.atStartOfDay().plusHours(fteExtraOvertimeSplitHour)
                    if (fteExtraOvertimeSplitHour != 24) {
                        if (scheduleRecord.fullStartDateTime.toLocalDate() == scheduleRecord.fullEndDateTime.toLocalDate() ||
                                (scheduleRecord.fullStartDateTime.toLocalDate() == scheduleRecord.fullEndDateTime.toLocalDate().minusDays(1) &&
                                        shiftEndsAtMidnight
                                )
                        ) {
                            // start /end on same date
                            if (fteExtraOvertimeSplitHour <= 24) {
                                // Split is before midnight
                                if (scheduleRecord.fullStartDateTime >= fteExtraOvertimeSplitDate) {
                                    scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                                    scheduleRecord.prevDayExtraHours = 0
                                    scheduleRecord.prevDayOvertimeHours = 0
                                    scheduleRecord.prevDayFteHours = 0
                                }
                            } else {
                                // split time after midnight
                                if (!(scheduleRecord.fullStartDateTime < fteExtraOvertimeSplitDate &&
                                        scheduleRecord.fullEndDateTime.toLocalDate() <= scheduleRecord.scheduleDate) &&
                                        scheduleRecord.fullStartDateTime < fteExtraOvertimeSplitDate.minusDays(1)
                                ) {
                                    scheduleRecord.prevDayDurationHours = scheduleRecord.durationHours
                                    scheduleRecord.nextDayExtraHours = 0
                                    scheduleRecord.nextDayOvertimeHours = 0
                                    scheduleRecord.nextDayFteHours = 0
                                }
                            }
                        } else {
                            // start and end date/time are on different dates
                            if (fteExtraOvertimeSplitHour < 24 &&
                                    scheduleRecord.fullStartDateTime >= fteExtraOvertimeSplitDate &&
                                    scheduleRecord.fullStartDateTime.toLocalDate() < scheduleRecord.scheduleDate.plusDays(1)
                            ) {
                                scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                                scheduleRecord.prevDayExtraHours = 0
                                scheduleRecord.prevDayOvertimeHours = 0
                                scheduleRecord.prevDayFteHours = 0
                            }
                        }
                    }
                }

                BigDecimal currentDurationHours = [Hours.between(scheduleRecord.fullStartDateTime, scheduleRecord.fullEndDateTime).amount - scheduleRecord.durationHours, 0].max()
                if (currentDurationHours > 0) {
                    if (scheduleRecord.prevDayDurationHours > 0) {
                        if (scheduleRecord.prevDayDurationHours >= currentDurationHours) {
                            scheduleRecord.prevDayDurationHours = scheduleRecord.prevDayDurationHours - currentDurationHours
                            currentDurationHours = 0
                        } else {
                            //TODO: Dead code? Can't hit if other potential dead code blocks can't be hit
                            scheduleRecord.prevDayDurationHours = 0
                            currentDurationHours = [currentDurationHours - scheduleRecord.prevDayDurationHours, 0].max()
                        }
                    }
                    if (currentDurationHours > 0) {
                        if (scheduleRecord.nextDayDurationHours > 0) {
                            if (scheduleRecord.nextDayDurationHours > scheduleRecord.durationHours) {
                                scheduleRecord.nextDayDurationHours = scheduleRecord.durationHours
                            }
                        }
                    }
                }
                break
        }

        return scheduleRecord
    }

    /**
     * SCH_SHIFT_RECOMPUTE.ZERO_OUT_ROW_HOURS
     *
     * @param scheduleRecord                - L_SCH_RECS
     * @param typeToClear                   - L_TYPE_TO_CLEAR
     * @param weekNumber                    - L_OT_WEEK_NUMBER
     * @param normalAccountingId            - L_NORMAL_SCH_ACCOUNTING_ID
     * @param extraAccountingId             - L_EXTRA_SCH_ACCOUNTING_ID
     * @param overtimeAccountingId          - L_OVERTIME_SCH_ACCOUNTING_ID
     * @param ftePeriodStartDate            - L_FTE_PERIOD_START_DATE
     * @param ftePeriodEndDate              - L_FTE_PERIOD_END_DATE
     */
    static void zeroOutRowHours(
            ScheduleRecordDTO scheduleRecord,
            ShiftRecomputeTypeToClear typeToClear,
            int weekNumber,
            long normalAccountingId,
            long extraAccountingId,
            long overtimeAccountingId,
            LocalDate ftePeriodStartDate,
            LocalDate ftePeriodEndDate
    ) {
        boolean clearExtraCurrentDate = true
        boolean clearExtraPrevDate = true
        boolean clearExtraNextDate = true
        boolean clearOvertimeCurrentDate = true
        boolean clearOvertimePrevDate = true
        boolean clearOvertimeNextDate = true

        if (typeToClear != CLEAR_ALL_EXTRA_AND_OT) {
            if (scheduleRecord.dateSignificance == LAST_DAY_OF_PREVIOUS_PERIOD) {
                clearExtraCurrentDate = false
                clearExtraPrevDate = false
                clearOvertimeCurrentDate = false
                clearOvertimePrevDate = false
            } else if (scheduleRecord.dateSignificance == FIRST_DAY_OF_NEXT_PERIOD) {
                clearExtraCurrentDate = false
                clearExtraNextDate = false
                clearOvertimeCurrentDate = false
                clearOvertimeNextDate = false
            } else if (scheduleRecord.scheduleDate == ftePeriodStartDate && typeToClear == EXTRA_ONLY) {
                clearExtraPrevDate = false
            } else if (scheduleRecord.scheduleDate == ftePeriodEndDate && typeToClear == EXTRA_ONLY) {
                clearExtraNextDate = false
            } else if (scheduleRecord.dateSignificance == FIRST_DAY_OF_WEEK &&
                    typeToClear != EXTRA_ONLY
            ) {
                if (weekNumber > 0) {
                    if (scheduleRecord.weekNumber == weekNumber) {
                        clearExtraPrevDate = false
                        clearOvertimePrevDate = false
                    } else if (scheduleRecord.weekNumber == (weekNumber + 1)) {
                        clearExtraCurrentDate = false
                        clearExtraNextDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimeNextDate = false
                    } else if (scheduleRecord.weekNumber == (weekNumber - 1)) {
                        clearExtraCurrentDate = false
                        clearExtraPrevDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimePrevDate = false
                    } else {
                        clearExtraCurrentDate = false
                        clearExtraPrevDate = false
                        clearExtraNextDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimePrevDate = false
                        clearOvertimeNextDate = false
                    }
                }
            } else if (scheduleRecord.dateSignificance == LAST_DAY_OF_WEEK && typeToClear != EXTRA_ONLY) {
                if (weekNumber > 0) {
                    if (scheduleRecord.weekNumber == weekNumber ) {
                        clearExtraNextDate = false
                        clearOvertimeNextDate = false
                    } else if (scheduleRecord.weekNumber == (weekNumber - 1) ) {
                        clearExtraCurrentDate = false
                        clearExtraPrevDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimePrevDate = false
                    } else if (scheduleRecord.weekNumber == (weekNumber + 1) ) {
                        clearExtraCurrentDate = false
                        clearExtraPrevDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimePrevDate = false
                    } else {
                        clearExtraCurrentDate = false
                        clearExtraPrevDate = false
                        clearExtraNextDate = false
                        clearOvertimeCurrentDate = false
                        clearOvertimePrevDate = false
                        clearOvertimeNextDate = false
                    }
                }
            }
        }

        if ([EXTRA_ONLY, EXTRA_AND_OT_BY_WEEK, CLEAR_ALL_EXTRA_AND_OT].contains(typeToClear)) {
            if (clearExtraCurrentDate) {
                if (scheduleRecord.curDayExtraHours != 0) {
                    scheduleRecord.curDayExtraHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
            if (clearExtraPrevDate) {
                if (scheduleRecord.prevDayExtraHours != 0) {
                    scheduleRecord.prevDayExtraHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
            if (clearExtraNextDate) {
                if (scheduleRecord.nextDayExtraHours != 0) {
                    scheduleRecord.nextDayExtraHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
        }

        if ([OVERTIME_ONLY, EXTRA_AND_OT_BY_WEEK, CLEAR_ALL_EXTRA_AND_OT].contains(typeToClear)) {
            if (clearOvertimeCurrentDate) {
                if (scheduleRecord.curDayOvertimeHours != 0) {
                    scheduleRecord.curDayOvertimeHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
            if (clearOvertimePrevDate) {
                if (scheduleRecord.prevDayOvertimeHours != 0) {
                    scheduleRecord.prevDayOvertimeHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
            if (clearOvertimeNextDate) {
                if (scheduleRecord.nextDayOvertimeHours != 0) {
                    scheduleRecord.nextDayOvertimeHours = 0
                    setUnknownWorkingDecision(scheduleRecord)
                }
            }
        }

        BigDecimal extraHoursSum = getTotalExtraHours(scheduleRecord)
        if (scheduleRecord.extraHours != extraHoursSum) {
            scheduleRecord.extraHours = extraHoursSum
            scheduleRecord.isModified = true
        }

        BigDecimal overtimeHoursSum = getTotalOvertimeHours(scheduleRecord)
        if (scheduleRecord.overtimeHours != overtimeHoursSum) {
            scheduleRecord.overtimeHours = overtimeHoursSum
            scheduleRecord.isModified = true
        }

        if (scheduleRecord.overtimeHours > 0) {
            if (scheduleRecord.accountingId != overtimeAccountingId) {
                scheduleRecord.accountingId = overtimeAccountingId
                scheduleRecord.isModified = true
            }
        } else if (scheduleRecord.extraHours > 0) {
            if (scheduleRecord.accountingId != extraAccountingId) {
                scheduleRecord.accountingId = extraAccountingId
                scheduleRecord.isModified = true
            }
        } else if (scheduleRecord.accountingId != normalAccountingId) {
            scheduleRecord.accountingId = normalAccountingId
            scheduleRecord.isModified = true
        }
    }

    /**
     *
     * @param fteWeekCount              - L_FTE_PERIOD_CALC
     * @param overtimeRule              - L_OT_HOURS_PER_DAY, L_OT_HOURS_PER_PERIOD, L_OT_WEEKS_PER_PERIOD
     * @param scheduleRecordList        - L_SCH_RECS
     * @param costCenterRecordList      - L_CC_RECS
     * @return weeklyExtraOvertimeMap   = L_OT_RECS
     *                                  = DAILY_OT_HOURS
     *                                  = SCHEDULED_HOURS
     */
    static List populateMaps(
            int fteWeekCount,
            OvertimeRule overtimeRule,
            List<ScheduleRecordDTO> scheduleRecordList,
            List<CostCenterRecordDTO> costCenterRecordList
    ) {
        Map<Integer, BigDecimal> weekDailyOvertimeMap = [:]
        Map<LocalDate, BigDecimal> scheduledHoursDateMap = buildScheduledHoursDateMap(scheduleRecordList)
        Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap =
                populateWeeklyExtraOvertimeMapTotalWeekHours(fteWeekCount, scheduleRecordList)

        for (int weekNumber = 1; weekNumber <= fteWeekCount; weekNumber++) {
            weekDailyOvertimeMap.put(weekNumber, 0)

            if (weeklyExtraOvertimeMap.get(weekNumber).totalWeekHours > overtimeRule.hoursPerPeriod) {
                //Weekly total hours greating than rule's hours per period; difference is overtime
                weeklyExtraOvertimeMap.get(weekNumber).overtimeHours = weeklyExtraOvertimeMap.get(weekNumber).totalWeekHours - overtimeRule.hoursPerPeriod
            }
            if (overtimeRule.hoursPerDay > 0) {
                for (ScheduleRecordDTO scheduleRecord : scheduleRecordList.findAll {
                    it.weekNumber == weekNumber
                }) {
                    if (scheduledHoursDateMap.get(scheduleRecord.scheduleDate) > overtimeRule.hoursPerDay) {
                        //Hours scheduled on a single day greater than rule's hours per day; difference is overtime
                        scheduleRecord.overtimeHours = scheduledHoursDateMap.get(scheduleRecord.scheduleDate) - overtimeRule.hoursPerDay
                        weekDailyOvertimeMap.put(weekNumber, weekDailyOvertimeMap.get(weekNumber) + scheduleRecord.overtimeHours)
                    }
                }
            }

            if (overtimeRule.weeksPerPeriod > 1 && overtimeRule.weeksPerPeriod != weekNumber) {
                //Rule is for more than one week; total hours across period
                int overtimeRulWeekIndex = overtimeRule.weeksPerPeriod.toInteger()
                weeklyExtraOvertimeMap.get(overtimeRulWeekIndex).totalWeekHours += weeklyExtraOvertimeMap.get(weekNumber).totalWeekHours
                weekDailyOvertimeMap.put(overtimeRulWeekIndex, (weekDailyOvertimeMap.get(overtimeRulWeekIndex) ?: 0.0) + weekDailyOvertimeMap.get(weekNumber))
            }
        }

        BigDecimal extraTarget = 0
        if (overtimeRule.useExtraTime) {
            extraTarget = [
                    weeklyExtraOvertimeMap.values().totalWeekHours.sum() -
                            (fteWeekCount * 40) * (costCenterRecordList.fte.sum() as BigDecimal) -
                            (weeklyExtraOvertimeMap.values().overtimeHours.sum() as BigDecimal),
                    0
            ].max()
        }

        for (int weekNumber = fteWeekCount; weekNumber >= 1; weekNumber--) {
            weeklyExtraOvertimeMap.get(weekNumber).extraHours = [
                    weeklyExtraOvertimeMap.get(weekNumber).totalWeekHours,
                    extraTarget
            ].min()
            extraTarget -= weeklyExtraOvertimeMap.get(weekNumber).extraHours
        }

        return [weeklyExtraOvertimeMap, weekDailyOvertimeMap, scheduledHoursDateMap]
    }

    /**
     * Create and return a map pairing dates to a sum of all duration hours currently scheduled on said date.
     *
     * @param scheduleRecordList    - L_SCH_RECS
     * @return                      = SCHEDULED_HOURS
     */
    private static Map<LocalDate, BigDecimal> buildScheduledHoursDateMap(List<ScheduleRecordDTO> scheduleRecordList) {
        Map<LocalDate, BigDecimal> scheduledHoursDateMap = [:]
        for (LocalDate scheduleDate : scheduleRecordList.scheduleDate.unique()) {
            scheduledHoursDateMap.put(scheduleDate, scheduleRecordList.findAll { it.scheduleDate == scheduleDate }.durationHours.sum() as BigDecimal)
        }
        return scheduledHoursDateMap
    }

    /**
     * Populate totalWeekHours by week as the sum of duration hours on current, next, or previous day
     * based on where in the week/period the scheduled shift lies.
     *
     * @param fteWeekCount              - L_FTE_PERIOD_CALC
     * @param scheduleRecordList        - L_SCH_RECS
     * @return                          = L_OT_RECS
     */
    static Map<Integer, OvertimeRecordDTO> populateWeeklyExtraOvertimeMapTotalWeekHours(
            int fteWeekCount,
            List<ScheduleRecordDTO> scheduleRecordList
    ) {
        Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap = [:]
        for (int weekNumber = 1; weekNumber <= fteWeekCount; weekNumber++) {
            weeklyExtraOvertimeMap.put(weekNumber, new OvertimeRecordDTO())
        }
        for (ScheduleRecordDTO scheduleRecord : scheduleRecordList) {
            if (!scheduleRecord.adjustUpToFte) {
                switch (scheduleRecord.dateSignificance) {
                    case LAST_DAY_OF_PREVIOUS_PERIOD:
                        //Only count hours that trail into current period
                        weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber + 1).totalWeekHours +=
                                scheduleRecord.nextDayDurationHours
                        break
                    case FIRST_DAY_OF_NEXT_PERIOD:
                        //Only count hours that come before next period
                        weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber - 1).totalWeekHours +=
                                scheduleRecord.prevDayDurationHours
                        break
                    case FIRST_DAY_OF_WEEK:
                        //Don't count hours for previous week; if previous week is in current period, apply them there instead.
                        weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber).totalWeekHours +=
                                scheduleRecord.durationHours - scheduleRecord.prevDayDurationHours
                        if (scheduleRecord.weekNumber > 1) {
                            weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber - 1).totalWeekHours +=
                                    scheduleRecord.prevDayDurationHours
                        }
                        break
                    case LAST_DAY_OF_WEEK:
                        //Don't count hours for next week; if next week is in current period, apply them there instead.
                        weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber).totalWeekHours +=
                                scheduleRecord.durationHours - scheduleRecord.nextDayDurationHours
                        if (scheduleRecord.weekNumber < fteWeekCount) {
                            weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber + 1).totalWeekHours +=
                                    scheduleRecord.nextDayDurationHours
                        }
                        break
                    default:
                        weeklyExtraOvertimeMap.get(scheduleRecord.weekNumber).totalWeekHours +=
                                scheduleRecord.durationHours
                        break
                }
            }
        }
        return weeklyExtraOvertimeMap
    }

    /**
     * If current overtime rule uses extra time, return the total sum of the user's cost center fte
     *
     * @param overtimeRule          - L_OT_USE_EXTRA_TIME
     * @param costCenterRecordList  - L_CC_RECS
     * @param fteWeekCount          - L_FTE_PERIOD_CALC
     * @return                      = L_EXTRA_FTE_TARGET
     */
    static BigDecimal getExtraFteTarget(
            OvertimeRule overtimeRule,
            List<CostCenterRecordDTO> costCenterRecordList,
            int fteWeekCount
    ) {
        BigDecimal extraFteTarget = 0
        if (overtimeRule.useExtraTime) {
            BigDecimal totalFte = (costCenterRecordList?.fte?.sum() ?: 0.0) as BigDecimal
            extraFteTarget = (fteWeekCount * 40) * totalFte
        }
        return extraFteTarget
    }

    /**
     *
     * @param weeklyExtraOvertimeMap    - L_OT_RECS
     * @return                          = L_EXTRA_HOURS_TO_MARK
     */
    static BigDecimal getExtraHoursToMark(Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap) {
        BigDecimal extraHoursToMark = weeklyExtraOvertimeMap.values().extraHours.sum() as BigDecimal
        if (extraHoursToMark < 0) {
            return 0
        } else {
            return extraHoursToMark
        }
    }

    /**
     * Calculates and applies overtime hours in a record for current, previous, and next days.
     * Values returned will be affected by extra hours as well, and ultimately used to determine worked duration hours.
     *
     * @param fteWeekCount              - L_FTE_PERIOD_CALC
     * @param overtimeRule              - L_OT_WEEKS_PER_PERIOD
     * @param scheduleRecordList        - L_SCH_RECS
     * @param weeklyExtraOvertimeMap    - L_OT_RECS
     * @param normalAccountingId        - L_NORMAL_SCH_ACCOUNTING_ID
     * @param extraAccountingId         - L_EXTRA_SCH_ACCOUNTING_ID
     * @param overtimeAccountingId      - L_OVERTIME_SCH_ACCOUNTING_ID
     * @return                          = T_WORKING_OT_HOURS, L_FORCED_NEXTDAY_HRS, L_FORCED_PREVDAY_HRS, L_FORCED_CURDAY_HRS
     */
    static List setOvertimeHours(
            int fteWeekCount,
            OvertimeRule overtimeRule,
            List<ScheduleRecordDTO> scheduleRecordList,
            Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap,
            long normalAccountingId,
            long extraAccountingId,
            long overtimeAccountingId
    ) {
        BigDecimal processingOvertimeHours = 0
        BigDecimal forcedCurrentDayHours = 0
        BigDecimal forcedPrevDayHours = 0
        BigDecimal forcedNextDayHours = 0

        for (int weekNumber = fteWeekCount; weekNumber >= 1; weekNumber--) {
            // For each week in FTE period, starting with the latest
            if (weeklyExtraOvertimeMap.get(weekNumber).overtimeHours > 0) {
                //Overtime hours found for week
                for (ScheduleRecordDTO scheduleRecord : scheduleRecordList) {
                    if (scheduleRecord.accountingIdChanged &&
                            scheduleRecord.accountingId == overtimeAccountingId &&
                            weeklyExtraOvertimeMap.get(weekNumber).overtimeHours > 0 &&
                            scheduleRecord.durationHours > 0
                    ) {
                        //Record is set for overtime and there are still overtime hours left on the week
                        def (
                        int processingWeekNumber,
                        BigDecimal currentDateOvertimeHours,
                        BigDecimal currentDateDurationHours
                        ) = getCurrentDurationAndOvertimeHours(
                                weekNumber,
                                scheduleRecord
                        )

                        if (processingWeekNumber == scheduleRecord.weekNumber) {
                            //Process overtime hours for the current week

                            markScheduleDecisionLeaveAlone(scheduleRecord, 1)

                            //Get amount of overtime hours left to process for current day
                            processingOvertimeHours = getProcessingOvertimeHours(
                                    weeklyExtraOvertimeMap,
                                    weekNumber,
                                    overtimeRule,
                                    currentDateOvertimeHours,
                                    currentDateDurationHours
                            )

                            if (currentDateOvertimeHours != processingOvertimeHours) {
                                //TODO: isModified won't always be set when updating overtime values below; is this okay?
                                scheduleRecord.isModified = true
                            }

                            switch (scheduleRecord.dateSignificance) {
                                case LAST_DAY_OF_PREVIOUS_PERIOD:
                                    //Last day or previous period; it can have overtime hours in current period
                                    scheduleRecord.nextDayOvertimeHours = processingOvertimeHours
                                    forcedNextDayHours += processingOvertimeHours
                                    break
                                case FIRST_DAY_OF_NEXT_PERIOD:
                                    //First day of next period; it can have overtime hours in current period
                                    scheduleRecord.prevDayOvertimeHours = processingOvertimeHours
                                    forcedPrevDayHours += processingOvertimeHours
                                    break
                                case FIRST_DAY_OF_WEEK:
                                    //First day of week
                                    if (scheduleRecord.weekNumber == weekNumber) {
                                        //Current week
                                        scheduleRecord.curDayOvertimeHours = processingOvertimeHours
                                        scheduleRecord.nextDayOvertimeHours = 0
                                        forcedCurrentDayHours += processingOvertimeHours
                                    } else if (scheduleRecord.weekNumber == (weekNumber + 1)) {
                                        //Next week; can have overtime hours in current week
                                        scheduleRecord.prevDayOvertimeHours = processingOvertimeHours
                                        forcedPrevDayHours += processingOvertimeHours
                                    }
                                    break
                                case LAST_DAY_OF_WEEK:
                                    //Last day of week
                                    if (scheduleRecord.weekNumber == weekNumber) {
                                        //Current week
                                        scheduleRecord.curDayOvertimeHours = processingOvertimeHours
                                        scheduleRecord.prevDayOvertimeHours = 0
                                        forcedCurrentDayHours += processingOvertimeHours
                                    } else if (scheduleRecord.weekNumber == (weekNumber - 1)) {
                                        //Previous week; can have overtime hours in current week
                                        scheduleRecord.nextDayOvertimeHours = processingOvertimeHours
                                        forcedNextDayHours += processingOvertimeHours
                                    }
                                    break
                                default:
                                    //Normal day in period; not the start or end of anything
                                    scheduleRecord.curDayOvertimeHours = processingOvertimeHours
                                    scheduleRecord.nextDayOvertimeHours = 0
                                    scheduleRecord.prevDayOvertimeHours = 0
                                    forcedCurrentDayHours += processingOvertimeHours
                            }

                            scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)

                            weeklyExtraOvertimeMap.get(weekNumber).overtimeHours -= processingOvertimeHours

                            if (scheduleRecord.overtimeHours <= 0) {
                                markRecordDecisionUnknown(
                                        scheduleRecord,
                                        scheduleRecord.extraHours <= 0 ? normalAccountingId : extraAccountingId
                                )
                            }
                        }
                    }
                }
            }
        }

        [processingOvertimeHours, forcedNextDayHours, forcedPrevDayHours, forcedCurrentDayHours]
    }

    /**
     * Return values for current date's duration hours, overtime hours, and which week to process
     *
     * @param weekNumber        - WEEK_NO
     * @param scheduleRecord    - L_SCH_RECS(L_SCH_LOOP_CTR)
     * @return                  = L_CURRENT_DAY_OT_TOTAL, L_CURRENT_DAY_DURATION_HRS, L_WEEK_NO
     */
    static List getCurrentDurationAndOvertimeHours(
            int weekNumber,
            ScheduleRecordDTO scheduleRecord
    ) {
        int processingWeekNumber = weekNumber
        BigDecimal currentDateDurationHours = 0
        BigDecimal currentDateOvertimeTotal = 0
        BigDecimal currentDateOvertimeHours = [scheduleRecord.curDayOvertimeHours, 0].max()

        switch (scheduleRecord.dateSignificance) {
            case LAST_DAY_OF_PREVIOUS_PERIOD:
                //Use next day hours only
                //	Adjust processingWeekNumber so we process this entry for the other week
                currentDateDurationHours = scheduleRecord.nextDayDurationHours
                currentDateOvertimeTotal = scheduleRecord.nextDayOvertimeHours
                processingWeekNumber = weekNumber - 1
                break
            case FIRST_DAY_OF_NEXT_PERIOD:
                //Use previous day hours only
                //	Adjust processingWeekNumber so we process this entry for the other week
                currentDateDurationHours = scheduleRecord.prevDayDurationHours
                currentDateOvertimeTotal = scheduleRecord.prevDayOvertimeHours
                processingWeekNumber = weekNumber + 1
                break
            case FIRST_DAY_OF_WEEK:
                //FIRST DAY OF WEEK day during the period (may be week 1 or week 2)
                if (scheduleRecord.weekNumber == weekNumber) {
                    //In the week we are processing; Do not use PREVDAY Hours, only CURDAY + NEXTDAY
                    currentDateDurationHours = scheduleRecord.durationHours - scheduleRecord.prevDayDurationHours
                    currentDateOvertimeTotal = currentDateOvertimeHours + scheduleRecord.nextDayOvertimeHours

                } else if (scheduleRecord.weekNumber == (weekNumber + 1)) {
                    //In the week after we are processing; Use use PREVDAY Hours only
                    //		Adjust processingWeekNumber so we process this entry for the other week
                    currentDateDurationHours = scheduleRecord.prevDayDurationHours
                    currentDateOvertimeTotal = scheduleRecord.prevDayOvertimeHours
                    processingWeekNumber = weekNumber + 1
                }
                break
            case LAST_DAY_OF_WEEK:
                //LAST DAY OF WEEK day during the period (may be week 1 or week 2)
                if (scheduleRecord.weekNumber == weekNumber) {
                    //Schedule is in the week we are processing; Do not use NEXTDAY Hours, only CURDAY + PREVDAY
                    currentDateDurationHours = scheduleRecord.durationHours - scheduleRecord.nextDayDurationHours
                    currentDateOvertimeTotal = currentDateOvertimeHours + scheduleRecord.prevDayOvertimeHours
                } else if (scheduleRecord.weekNumber == (weekNumber - 1)) {
                    //Schedule is in the week prior to week we are processing; Use use NEXTDAY Hours only
                    //		Adjust processingWeekNumber so we process this entry for the other week
                    currentDateDurationHours = scheduleRecord.nextDayDurationHours
                    currentDateOvertimeTotal = scheduleRecord.nextDayOvertimeHours
                    processingWeekNumber = weekNumber - 1
                }
                break
            default:
                // Use all hours for this day
                currentDateDurationHours = scheduleRecord.durationHours
                currentDateOvertimeTotal = scheduleRecord.overtimeHours
                break
        }

        return [processingWeekNumber, currentDateOvertimeTotal, currentDateDurationHours]
    }

    /**
     * Determine if overtime hours to use in processing are from the current date or a specific week
     * in the FTE period.
     *
     * @param weeklyExtraOvertimeMap    - L_OT_RECS
     * @param weekNumber                - WEEK_NO
     * @param overtimeRule              - L_OT_HOURS_PER_PERIOD, L_OT_WEEKS_PER_PERIOD
     * @param currentDateOvertimeHours  - L_CURRENT_DAY_OT_TOTAL
     * @param currentDateDurationHours  - L_CURRENT_DAY_DURATION_HRS
     * @return                          = T_WORKING_OT_HOURS
     */
    static BigDecimal getProcessingOvertimeHours(
            Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap,
            int weekNumber,
            OvertimeRule overtimeRule,
            BigDecimal currentDateOvertimeHours,
            BigDecimal currentDateDurationHours
    ) {
        BigDecimal processingOvertimeHours
        if (weeklyExtraOvertimeMap.get(weekNumber).totalWeekHours <= overtimeRule.hoursPerPeriod &&
                overtimeRule.hoursPerDay > 0
        ) {
            //Overtime can happen per day and there are not enough duration hours for the week's overtime
            processingOvertimeHours = currentDateOvertimeHours
        } else {
            //Use lesser between current day's overtime and the week's overtime for the relevant week.
            processingOvertimeHours = [
                    weeklyExtraOvertimeMap.get(getOvertimePeriodWeek(overtimeRule, weekNumber)).overtimeHours,
                    currentDateDurationHours
            ].min()
        }
        return processingOvertimeHours
    }

    /**
     *
     * @param scheduleRecordList            - L_SCH_RECS
     * @param fteWeekCount                  - L_FTE_PERIOD_CALC
     * @param extraHoursToMark              - L_EXTRA_HOURS_TO_MARK
     * @param processingOvertimeHours       - T_WORKING_OT_HOURS
     * @param forcedNextDayOvertimeHours    - L_FORCED_NEXTDAY_HRS
     * @param forcedPrevDayOvertimeHours    - L_FORCED_PREVDAY_HRS
     * @param forcedCurrentOvertimeDayHours - L_FORCED_CURDAY_HRS
     * @param normalAccountingId            - L_NORMAL_SCH_ACCOUNTING_ID
     * @param extraAccountingId             - L_EXTRA_SCH_ACCOUNTING_ID
     * @return                              = L_FORCED_NEXTDAY_HRS,
     *                                      = L_FORCED_PREVDAY_HRS,
     *                                      = L_FORCED_CURDAY_HRS,
     *                                      = L_EXTRA_HOURS_TO_MARK
     */
    static List setExtraHours(
            List<ScheduleRecordDTO> scheduleRecordList,
            int fteWeekCount,
            BigDecimal extraHoursToMark,
            BigDecimal processingOvertimeHours,
            BigDecimal forcedNextDayOvertimeHours,
            BigDecimal forcedPrevDayOvertimeHours,
            BigDecimal forcedCurrentOvertimeDayHours,
            long normalAccountingId,
            long extraAccountingId
    ) {
        BigDecimal forcedNextDayHours = forcedNextDayOvertimeHours
        BigDecimal forcedPrevDayHours = forcedPrevDayOvertimeHours
        BigDecimal forcedCurrentDayHours = forcedCurrentOvertimeDayHours
        BigDecimal extraHoursToMarkRemaining = extraHoursToMark

        if (extraHoursToMark > 0) {
            for (ScheduleRecordDTO scheduleRecord : scheduleRecordList) {
                if (scheduleRecord.accountingIdChanged &&
                        scheduleRecord.accountingId == extraAccountingId &&
                        extraHoursToMarkRemaining > 0
                ) {
                    //Record is marked for extra hours and there are some remaining to set

                    boolean doProcessing = false
                    BigDecimal currentDateExtraTotal = 0
                    BigDecimal currentDateDurationHours
                    BigDecimal currentDateOvertimeTotal

                    switch (scheduleRecord.dateSignificance) {
                        case LAST_DAY_OF_PREVIOUS_PERIOD:
                            // Use the NEXTDAY hours only
                            currentDateDurationHours = scheduleRecord.nextDayDurationHours
                            currentDateOvertimeTotal = scheduleRecord.nextDayOvertimeHours
                            if (currentDateDurationHours - currentDateOvertimeTotal > 0) {
                                //There are OVERTIME hours, Make sure there are some left to assign to EXTRA to process
                                doProcessing = true
                                currentDateExtraTotal = scheduleRecord.nextDayExtraHours
                            }
                            break
                        case FIRST_DAY_OF_NEXT_PERIOD:
                            //Use the PREVDAY hours only
                            currentDateDurationHours = scheduleRecord.prevDayDurationHours
                            currentDateOvertimeTotal = scheduleRecord.prevDayOvertimeHours
                            if (currentDateDurationHours - currentDateOvertimeTotal > 0) {
                                //There are OVERTIME hours, Make sure there are some left to assign to EXTRA to process
                                doProcessing = true
                                currentDateExtraTotal = scheduleRecord.prevDayExtraHours
                            }
                            break
                        case FIRST_DAY_OF_WEEK:
                            //FIRST DAY OF WEEK day during the period (may be week 1 or week 2);
                            // Do not use PREVDAY Hours, only CURDAY + NEXTDAY
                            currentDateDurationHours = scheduleRecord.durationHours - scheduleRecord.prevDayDurationHours
                            currentDateOvertimeTotal = scheduleRecord.nextDayOvertimeHours + scheduleRecord.curDayOvertimeHours
                            if (currentDateDurationHours - currentDateOvertimeTotal > 0) {
                                //There are OVERTIME hours , Make sure there are some left to assign to EXTRA to process
                                doProcessing = true
                                currentDateExtraTotal = scheduleRecord.nextDayExtraHours + scheduleRecord.curDayExtraHours
                            }
                            break
                        case LAST_DAY_OF_WEEK:
                            //LAST DAY OF WEEK day during the period (may be week 1 or week 2);
                            // Do not use NEXTDAY Hours, only CURDAY + PREVDAY
                            currentDateDurationHours = scheduleRecord.durationHours - scheduleRecord.nextDayDurationHours
                            currentDateOvertimeTotal = scheduleRecord.prevDayOvertimeHours + scheduleRecord.curDayOvertimeHours
                            if (currentDateDurationHours - currentDateOvertimeTotal > 0) {
                                //There are OVERTIME hours, Make sure there are some left to assign to EXTRA to process
                                doProcessing = true
                                //TODO: Should this be: currentDateExtraTotal = scheduleRecord.prevDayExtraHours + scheduleRecord.curDayExtraHours
                                currentDateExtraTotal = scheduleRecord.prevDayExtraHours + scheduleRecord.curDayOvertimeHours
                            }
                            break
                        default:
                            // 	Use ALL DURATION hours
                            currentDateDurationHours = scheduleRecord.durationHours
                            currentDateOvertimeTotal = scheduleRecord.overtimeHours
                            if (currentDateDurationHours - currentDateOvertimeTotal > 0) {
                                //There are OVERTIME hours , Make sure there are some left to assign to EXTRA to process
                                doProcessing = true
                                currentDateExtraTotal = scheduleRecord.extraHours
                            }
                            break
                    }

                    if (doProcessing) {
                        markScheduleDecisionLeaveAlone(scheduleRecord, 2)

                        BigDecimal previousExtraTotal = currentDateExtraTotal
                        BigDecimal processingExtraHours = [
                                extraHoursToMarkRemaining,
                                currentDateDurationHours - currentDateOvertimeTotal
                        ].min()

                        if (previousExtraTotal != processingExtraHours) {
                            //TODO: It's possible to update record values without setting isModified here; is this okay?
                            scheduleRecord.isModified = true
                        }

                        switch (scheduleRecord.dateSignificance) {
                            case LAST_DAY_OF_PREVIOUS_PERIOD:
                                //Last day or previous period; it can have extra hours in current period
                                scheduleRecord.nextDayExtraHours = processingExtraHours
                                forcedNextDayHours += processingOvertimeHours
                                break
                            case FIRST_DAY_OF_NEXT_PERIOD:
                                //First day of next period; it can have extra hours in current period
                                scheduleRecord.prevDayExtraHours = processingExtraHours
                                forcedPrevDayHours += processingOvertimeHours
                                break
                            case FIRST_DAY_OF_WEEK:
                                if (scheduleRecord.weekNumber == 1) {
                                    //First week in period
                                    scheduleRecord.nextDayExtraHours = 0
                                } else {
                                    processingExtraHours += scheduleRecord.nextDayExtraHours + scheduleRecord.prevDayExtraHours
                                }

                                scheduleRecord.curDayExtraHours = processingExtraHours
                                forcedCurrentDayHours += processingOvertimeHours
                                break
                            case LAST_DAY_OF_WEEK:
                                if (scheduleRecord.weekNumber == fteWeekCount) {
                                    //Last week in period
                                    scheduleRecord.prevDayExtraHours = 0
                                } else {
                                    processingExtraHours += scheduleRecord.nextDayExtraHours + scheduleRecord.prevDayExtraHours
                                }

                                scheduleRecord.curDayExtraHours = processingExtraHours
                                forcedCurrentDayHours += processingOvertimeHours
                                break
                            default:
                                scheduleRecord.curDayExtraHours = processingExtraHours
                                scheduleRecord.nextDayExtraHours = 0
                                scheduleRecord.prevDayExtraHours = 0
                                forcedCurrentDayHours += processingOvertimeHours
                                break
                        }

                        scheduleRecord.extraHours = getTotalExtraHours(scheduleRecord)
                        extraHoursToMarkRemaining -= processingExtraHours

                        if (scheduleRecord.extraHours <= 0 &&
                                scheduleRecord.overtimeHours <= 0
                        ) {
                            //TODO: Dead code? processingExtraHours cannot be zero, and always get set to some extra hours value or another
                            markRecordDecisionUnknown(scheduleRecord, normalAccountingId)
                        }
                    }
                }
            }
        }

        [forcedNextDayHours, forcedPrevDayHours, forcedCurrentDayHours, extraHoursToMarkRemaining]
    }

    /**
     * Update schedule records' FTE hours values and primary cost center record's fte values
     * @param scheduleRecordList        - L_SCH_RECS
     * @param primaryCostCenterRecord   - L_CC_RECS(1)
     * @param fteWeekCount              - L_FTE_PERIOD_CALC
     * @param normalAccountingId        - L_NORMAL_SCH_ACCOUNTING_ID
     * @param extraAccountingId         - L_EXTRA_SCH_ACCOUNTING_ID
     * @param overtimeAccountingId      - L_OVERTIME_SCH_ACCOUNTING_ID
     * @param ftePeriodStartDate        - L_FTE_PERIOD_START_DATE
     * @param ftePeriodEndDate          - L_FTE_PERIOD_END_DATE
     */
    static void setFteHours(
            List<ScheduleRecordDTO> scheduleRecordList,
            CostCenterRecordDTO primaryCostCenterRecord,
            long normalAccountingId,
            long extraAccountingId,
            long overtimeAccountingId,
            LocalDate ftePeriodStartDate,
            LocalDate ftePeriodEndDate
    ) {
        for (ScheduleRecordDTO scheduleRecord : scheduleRecordList) {
            BigDecimal currentDayFteHours
            BigDecimal otherDayFteHours = 0

            switch (scheduleRecord.dateSignificance) {
                case LAST_DAY_OF_PREVIOUS_PERIOD:
                    currentDayFteHours = scheduleRecord.nextDayDurationHours
                    otherDayFteHours = scheduleRecord.durationHours - scheduleRecord.nextDayDurationHours
                    break
                case FIRST_DAY_OF_NEXT_PERIOD:
                    currentDayFteHours = scheduleRecord.prevDayDurationHours
                    otherDayFteHours = scheduleRecord.durationHours - scheduleRecord.prevDayDurationHours
                    break
                case FIRST_DAY_OF_WEEK:
                    currentDayFteHours = scheduleRecord.durationHours - scheduleRecord.prevDayDurationHours
                    otherDayFteHours = scheduleRecord.prevDayDurationHours
                    break
                case LAST_DAY_OF_WEEK:
                    currentDayFteHours = scheduleRecord.durationHours - scheduleRecord.nextDayDurationHours
                    otherDayFteHours = scheduleRecord.nextDayDurationHours
                    break
                default:
                    currentDayFteHours = scheduleRecord.durationHours
                    scheduleRecord.nextDayFteHours = 0
                    scheduleRecord.prevDayFteHours = 0
                    break
            }

            if (scheduleRecord.adjustUpToFte && scheduleRecord.accountingId != normalAccountingId) {
                zeroOutRowHours(
                        scheduleRecord,
                        CLEAR_ALL_EXTRA_AND_OT,
                        0,
                        normalAccountingId,
                        extraAccountingId,
                        overtimeAccountingId,
                        ftePeriodStartDate,
                        ftePeriodEndDate)
            }

            primaryCostCenterRecord.fteHoursRemain -= currentDayFteHours

            if (primaryCostCenterRecord.fteHoursRemain < 0) {
                if (scheduleRecord.adjustUpToFte) {
                    currentDayFteHours += primaryCostCenterRecord.fteHoursRemain
                }
                primaryCostCenterRecord.fteHoursRemain = 0
            }

            setFteHours(scheduleRecord, currentDayFteHours, otherDayFteHours)
        }
    }

    /**
     * Set the record's FTE hours for current, previous, and next days, as appropriate.
     *
     * @param scheduleRecord        - L_SCH_RECS(L_SCH_LOOP_CTR)
     * @param currentDayFteHours    - L_CURRENT_DAY_FTEHRS
     * @param otherDayFteHours      - L_OTHER_DAY_FTEHRS
     * @return                      = L_SCH_RECS(L_SCH_LOOP_CTR)
     */
    static void setFteHours(
            ScheduleRecordDTO scheduleRecord,
            BigDecimal currentDayFteHours,
            BigDecimal otherDayFteHours
    ) {
        switch (scheduleRecord.dateSignificance) {
            case LAST_DAY_OF_PREVIOUS_PERIOD:
                if (scheduleRecord.nextDayFteHours != currentDayFteHours) {
                    scheduleRecord.nextDayFteHours = currentDayFteHours
                    scheduleRecord.isModified = true
                }
                scheduleRecord.curDayFteHours = otherDayFteHours
                scheduleRecord.prevDayFteHours = 0
                break
            case FIRST_DAY_OF_NEXT_PERIOD:
                if (scheduleRecord.prevDayFteHours != currentDayFteHours) {
                    scheduleRecord.prevDayFteHours = currentDayFteHours
                    scheduleRecord.isModified = true
                }
                scheduleRecord.curDayFteHours = otherDayFteHours
                scheduleRecord.nextDayFteHours = 0
                break
            case FIRST_DAY_OF_WEEK:
                scheduleRecord.isModified = true
                scheduleRecord.curDayFteHours = currentDayFteHours
                scheduleRecord.nextDayFteHours = 0
                scheduleRecord.prevDayFteHours = otherDayFteHours
                break
            case LAST_DAY_OF_WEEK:
                scheduleRecord.isModified = true
                scheduleRecord.curDayFteHours = currentDayFteHours
                scheduleRecord.nextDayFteHours = otherDayFteHours
                scheduleRecord.prevDayFteHours = 0
                break
            default:
                scheduleRecord.curDayFteHours =
                        currentDayFteHours +
                                scheduleRecord.nextDayFteHours +
                                scheduleRecord.prevDayFteHours
                break
        }

        BigDecimal totalFteHours = getTotalFteHours(scheduleRecord)
        if (scheduleRecord.fteHours != totalFteHours) {
            scheduleRecord.fteHours = totalFteHours
            scheduleRecord.isModified = true
        }
    }

    /**
     *
     * @param scheduleRecord            - L_SCH_RECS(L_SCH_LOOP_CTR)
     * @param overtimeRule              - L_OT_WEEKS_PER_PERIOD
     * @param weekNumber                - WEEK_NO
     * @param processingWeekNumber      - L_WEEK_NO
     * @param overtimeAccountingId      - L_OVERTIME_SCH_ACCOUNTING_ID
     * @param overtimeAdjustAmount      - L_OVER_ADJ_AMOUNT
     * @param currentDateOvertimeHours  - L_CURDAY_OT
     * @param currentDateOvertimeTotal  - L_CURRENT_DAY_OT_TOTAL
     */
    static void setOvertime(
            ScheduleRecordDTO scheduleRecord,
            OvertimeRule overtimeRule,
            int weekNumber,
            int processingWeekNumber,
            long overtimeAccountingId,
            BigDecimal overtimeAdjustAmount,
            BigDecimal currentDateOvertimeHours,
            BigDecimal currentDateOvertimeTotal
    ) {
        if (overtimeAdjustAmount > 0 && scheduleRecord.accountingId != overtimeAccountingId) {
            scheduleRecord.accountingId = overtimeAccountingId
            scheduleRecord.isModified = true
        }

        switch (scheduleRecord.dateSignificance) {
            case LAST_DAY_OF_PREVIOUS_PERIOD:
                if (currentDateOvertimeTotal != overtimeAdjustAmount) {
                    scheduleRecord.isModified = true
                    scheduleRecord.nextDayOvertimeHours = overtimeAdjustAmount
                }

                //TODO: Should scheduleRecord.curDayOvertimeHours be set to currentDateOvertimeHours?
                BigDecimal totalOvertimeHours =
                        currentDateOvertimeHours +
                                scheduleRecord.nextDayOvertimeHours +
                                scheduleRecord.prevDayOvertimeHours
                if (totalOvertimeHours != scheduleRecord.overtimeHours) {
                    scheduleRecord.overtimeHours = totalOvertimeHours
                    scheduleRecord.isModified = true
                }
                break
            case FIRST_DAY_OF_NEXT_PERIOD:
                if (currentDateOvertimeTotal != overtimeAdjustAmount) {
                    scheduleRecord.isModified = true
                    scheduleRecord.prevDayOvertimeHours = overtimeAdjustAmount
                }

                //TODO: Should scheduleRecord.curDayOvertimeHours be set to currentDateOvertimeHours?
                BigDecimal totalOvertimeHours =
                        currentDateOvertimeHours +
                                scheduleRecord.nextDayOvertimeHours +
                                scheduleRecord.prevDayOvertimeHours
                if (totalOvertimeHours != scheduleRecord.overtimeHours) {
                    scheduleRecord.overtimeHours = totalOvertimeHours
                    scheduleRecord.isModified = true
                }
                break
            case FIRST_DAY_OF_WEEK:
                if (scheduleRecord.weekNumber == weekNumber) {
                    scheduleRecord.curDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.nextDayOvertimeHours = 0
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                } else if (scheduleRecord.weekNumber == processingWeekNumber) {
                    scheduleRecord.prevDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                } else if (isFirstWeekInMultiWeekRule(overtimeRule, weekNumber)) {
                    scheduleRecord.curDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.prevDayOvertimeHours = 0
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                }
                break
            case LAST_DAY_OF_WEEK:
                if (scheduleRecord.weekNumber == weekNumber) {
                    scheduleRecord.curDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.prevDayOvertimeHours = 0
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                } else if (scheduleRecord.weekNumber == processingWeekNumber) {
                    scheduleRecord.nextDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                } else if (isFirstWeekInMultiWeekRule(overtimeRule, weekNumber)) {
                    scheduleRecord.curDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.nextDayOvertimeHours = 0
                    scheduleRecord.isModified = true
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                }
                break
            default:
                if (currentDateOvertimeTotal != overtimeAdjustAmount) {
                    scheduleRecord.isModified = true
                    scheduleRecord.curDayOvertimeHours = overtimeAdjustAmount
                    scheduleRecord.overtimeHours = getTotalOvertimeHours(scheduleRecord)
                }
                break
        }
    }

    /**
     * Get amount to adjust overtime by. Removeadjustment from current week overtime count and record's duration.
     *
     * @param weekNumber                - WEEK_NO
     * @param processingWeekNumber      - L_WEEK_NO
     * @param overtimePeriodWeek        - L_OT_PERIOD_WEEK
     * @param overtimeRule              - L_OT_WEEKS_PER_PERIOD, L_OT_HOURS_PER_DAY
     * @param scheduleRecord            - L_SCH_RECS
     * @param weeklyExtraOvertimeMap    - L_OT_RECS
     * @param weekDailyOvertimeMap      - DAILY_OT_HOURS
     * @param scheduledHoursDateMap     - SCHEDULED_HOURS
     * @return                          = L_OVER_ADJ_AMOUNT
     */
    static BigDecimal getOvertimeAdjustAmount(
            int weekNumber,
            int processingWeekNumber,
            int overtimePeriodWeek,
            OvertimeRule overtimeRule,
            ScheduleRecordDTO scheduleRecord,
            Map<Integer, OvertimeRecordDTO> weeklyExtraOvertimeMap,
            Map<Integer, BigDecimal> weekDailyOvertimeMap,
            Map<LocalDate, BigDecimal> scheduledHoursDateMap
    ) {
        BigDecimal overtimeAdjustAmount = 0
        if ((overtimeRule.weeksPerPeriod == 1 || weekNumber == 1) &&
                scheduleRecord.workDurationHours >= 0 &&
                (weeklyExtraOvertimeMap.get(weekNumber).overtimeHours > 0 ||
                        (scheduledHoursDateMap.get(scheduleRecord.scheduleDate) > overtimeRule.hoursPerDay &&
                                overtimeRule.hoursPerDay > 0) ||
                        (weeklyExtraOvertimeMap.get(overtimeRule.weeksPerPeriod.toInteger()).overtimeHours > 0 &&
                                overtimeRule.weeksPerPeriod > 1)
                )
        ) {
            //First week in period (last to process) or rule is for one week at a time,
            // and there is overtime to assign in the week/day associated with the current overtime rule.
            if (overtimeRule.hoursPerDay > 0 && weekNumber == processingWeekNumber) {
                //Daily overtime to consider; adjustment calculated based on overtime for the week, the day, and
                // worked duration hours remaining on the scheduled shift.
                overtimeAdjustAmount = [
                        [
                                weeklyExtraOvertimeMap.get(overtimePeriodWeek).overtimeHours -
                                        weekDailyOvertimeMap.get(overtimePeriodWeek) +
                                        [0, (scheduledHoursDateMap.get(scheduleRecord.scheduleDate) - overtimeRule.hoursPerDay)].max(),
                                scheduleRecord.workDurationHours
                        ].min(),
                        scheduledHoursDateMap.get(scheduleRecord.scheduleDate) - overtimeRule.hoursPerDay
                ].max()

                scheduledHoursDateMap.put(
                        scheduleRecord.scheduleDate,
                        scheduledHoursDateMap.get(scheduleRecord.scheduleDate) - scheduleRecord.durationHours
                )
            } else {
                //No daily overtime to consider; adjustment is full duration of sche schedule maximum; remaining
                // overtime for the week otherwise.
                overtimeAdjustAmount = [
                        weeklyExtraOvertimeMap.get(overtimePeriodWeek).overtimeHours,
                        scheduleRecord.workDurationHours
                ].min()
            }

            overtimeAdjustAmount = [overtimeAdjustAmount, 0].max()

            weeklyExtraOvertimeMap.get(overtimePeriodWeek).overtimeHours -= overtimeAdjustAmount
            scheduleRecord.workDurationHours -= overtimeAdjustAmount
        }
        return overtimeAdjustAmount
    }

    /**
     * Find all dates that will be passed to SCH_FTE_ACCT_CONTROL.POPULATE_FTE_SINGLE_NO_COMMIT
     * for provided schedule record.
     * Also sets prev/next day duration/fte hours to zero where appropriate.
     *
     * @param scheduleRecord - L_SCH_RECS(SCH_IDX)
     * @return               = L_FTE_ACCT_GRID_DATES
     */
    static List<LocalDate> getFteAcctGridDates(ScheduleRecordDTO scheduleRecord) {
        List<LocalDate> fteAcctGridDates = []
        switch (scheduleRecord.dateSignificance) {
            case FIRST_DAY_OF_WEEK:
                scheduleRecord.nextDayDurationHours = 0
                if (scheduleRecord.origNextDayFteHours != 0) {
                    scheduleRecord.isModified = true
                }

                if (scheduleRecord.origPrevDayFteHours != scheduleRecord.prevDayFteHours) {
                    fteAcctGridDates.add(scheduleRecord.scheduleDate.minusDays(1))
                }
                break
            case LAST_DAY_OF_WEEK:
                scheduleRecord.prevDayDurationHours = 0
                //TODO: For isModified, should this check fte hours like the one above? Or should the one above do duration hours?
                if (scheduleRecord.origPrevDayDurationHours != 0) {
                    scheduleRecord.isModified = true
                }

                if (scheduleRecord.origNextDayFteHours != scheduleRecord.nextDayFteHours) {
                    fteAcctGridDates.add(scheduleRecord.scheduleDate.plusDays(1))
                }
                break
            case NONE:
                scheduleRecord.prevDayDurationHours = 0
                scheduleRecord.prevDayFteHours = 0
                scheduleRecord.nextDayDurationHours = 0
                scheduleRecord.nextDayFteHours = 0

                if (scheduleRecord.origPrevDayFteHours != 0) {
                    fteAcctGridDates.add(scheduleRecord.scheduleDate.minusDays(1))
                    scheduleRecord.isModified = true
                }
                if (scheduleRecord.origNextDayFteHours != 0) {
                    fteAcctGridDates.add(scheduleRecord.scheduleDate.plusDays(1))
                    scheduleRecord.isModified = true
                }
                break
        }

        return fteAcctGridDates
    }

    /**
     *
     * @param scheduleRecord    - L_SCH_RECS(L_SCH_LOOP_CTR)
     * @param extraHoursToMark  - L_EXTRA_HOURS_TO_MARK
     * @return                  = L_EXTRA_ADJ_AMOUNT
     */
    static BigDecimal getExtraAdjustAmount(ScheduleRecordDTO scheduleRecord, BigDecimal extraHoursToMark) {
        BigDecimal extraAdjustAmount = 0
        if (scheduleRecord.workDurationHours >= 0 &&
                extraHoursToMark > 0 &&
                (scheduleRecord.workingDecision != 'LEAVE_ALONE' || scheduleRecord.leaveAlone == 1)
        ) {
            extraAdjustAmount = [extraHoursToMark, scheduleRecord.workDurationHours].min()
        }
        return extraAdjustAmount
    }

    private static void setUnknownWorkingDecision(ScheduleRecordDTO scheduleRecord) {
        scheduleRecord.isModified = true
        scheduleRecord.workingDecision = 'UNKNOWN'
    }

    private static void markRecordDecisionUnknown(ScheduleRecordDTO scheduleRecord, long accountingId) {
        scheduleRecord.accountingId = accountingId
        scheduleRecord.isModified = true
        scheduleRecord.workingDecision = 'UNKNOWN'
    }

    private static void markScheduleDecisionLeaveAlone(ScheduleRecordDTO scheduleRecord, int leaveAloneIndex) {
        scheduleRecord.workingDecision = 'LEAVE_ALONE'
        scheduleRecord.leaveAlone = leaveAloneIndex
    }

    /**
     * If overtime rule is for more than one week, return index of last week. Otherwise, use current week.
     * @param overtimeRule  - L_OT_WEEKS_PER_PERIOD
     * @param weekNumber    - WEEK_NO
     * @return              = L_OT_PERIOD_WEEK
     */
    static int getOvertimePeriodWeek(OvertimeRule overtimeRule, int weekNumber) {
        overtimeRule.weeksPerPeriod > 1 ? overtimeRule.weeksPerPeriod : weekNumber
    }

    static boolean isFirstWeekInMultiWeekRule(OvertimeRule overtimeRule, int weekNumber) {
        return overtimeRule.weeksPerPeriod > 1 && weekNumber == 1
    }

    static BigDecimal getTotalExtraHours(ScheduleRecordDTO scheduleRecord) {
        return scheduleRecord.curDayExtraHours + scheduleRecord.nextDayExtraHours + scheduleRecord.prevDayExtraHours
    }

    static BigDecimal getTotalOvertimeHours(ScheduleRecordDTO scheduleRecord) {
        return scheduleRecord.curDayOvertimeHours + scheduleRecord.nextDayOvertimeHours + scheduleRecord.prevDayOvertimeHours
    }

    static BigDecimal getTotalFteHours(ScheduleRecordDTO scheduleRecord) {
        return scheduleRecord.curDayFteHours + scheduleRecord.nextDayFteHours + scheduleRecord.prevDayFteHours
    }
}