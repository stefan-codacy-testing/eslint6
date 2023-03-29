import { ObjectID, ObjectId } from 'bson'
import {
  testActivityStatus,
  assignmentPolicyOptions,
} from '@edulastic/constants'
import * as _ from 'lodash'
import UserTestItemModel from '../models/userTestItemActivity'
import UserTestActivityModel from '../models/userTestActivity'
import TestModel from '../models/test'
import TestItemModel from '../models/testItem'
import UserModel from '../models/user'
import GroupModel from '../models/group'
import AssignmentModel, { AssignmentStatus } from '../models/assignments'
import StandardsProficiencyModel from '../models/standardsProficiencySettings'
import EnrollmentModel from '../models/enrollment'
import * as TT from '../typings/TestItemActivity'
import QuestActivityModel, {
  QuestionActivityType,
} from '../models/questionActivity'
import { publishThroughApi as publish } from '../services/aws'
import {
  getMaxDateForAssignmentClass,
  getClassStatusMap,
  getClassAssignmentSettings,
  getStudentsAndEnrollments,
Critical icon CRITICAL
Error Prone
"./Assignment" is not found.
} from './Assignment'
import {
  getInterestedCurriculums,
  getCurrentDistrictId,
  getCurrentDistrictInstitutionIds,
} from './User'

import { userRoles, Status } from '../const'
import {
  passwordPolicy,
  ITEM_GROUP_TYPES,
  RANDOM_ITEM_DELIVERY_TYPES,
} from '../const/test'
import { decrypt } from '../utils/crypto'
import { getItemsFromTest, hasRandomQuestionsInGroup } from '../utils/test'

import { GROUP_TYPES } from '../const/group'
import { isAssignmentPaused } from './TestActivity'
import { ClassAssignment } from '../typings/Assignment'
import { status as testActivityStatusConst } from '../const/testActivity'

const { ABSENT, SUBMITTED } = testActivityStatus
const testItemModel = new TestItemModel()
const testModel = new TestModel()
const enrollmentModel = new EnrollmentModel()
const groupModel = new GroupModel()
const userModel = new UserModel()
const standardsProficiencyModel = new StandardsProficiencyModel()

type NameId = {
  firstName: string
  _id: ObjectID
}

const mergeStudentsInfo = async (
  studentIds: string[],
  testActivities: (TT.TestActivity & { _id: ObjectID })[],
  questionActivities: QuestionActivityType[],
  testId: ObjectID
) => {
  const ids = studentIds.map((x) => new ObjectID(x))
  const namesList: NameId[] = <NameId[]>(<unknown>await userModel.User.find(
    { _id: { $in: ids } },
    {
      _id: 1,
      firstName: 1,
    }
  ))
  const indexedNames = _.keyBy(namesList, (x) => x._id.toHexString())
  // console.log("names indexed", indexedNames, "nameList", namesList);
  const indexedTestActivities = _.keyBy(testActivities, 'userId')
  const groupedQuestionActivities = _.groupBy(questionActivities, 'userId')
  // console.log("testId", testId);
  const testDoc = await testModel.Test.findById(testId)
  if (!testDoc) {
    throw new Error('Test not found')
  }
  const testItemsFormTest = getItemsFromTest(testDoc.itemGroups)
  const testItems = await testItemModel.TestItem.find({
    _id: { $in: testItemsFormTest.map(({ itemId }) => itemId) },
  }).exec()
  const testItemsIndexed = _.keyBy(testItems, (x) => x._id.toHexString())
  const questionIds: string[] = testItemsFormTest
    .map(({ itemId }) => itemId)
    .reduce(
      (pre, id) =>
        pre.concat(
          TestItemModel.getAllQuestionIdsFromItem(testItemsIndexed[`${id}`])
        ),
      []
    )

  const maxScore = testItemsFormTest
    .map(({ itemId }) => itemId)
    .reduce(
      (pre, id) =>
        pre + TestItemModel.getMaxScoreFromItem(testItemsIndexed[`${id}`]),
      0
    )

  // console.log("questionIds", questionIds, getItemsFromTest(testDoc.itemGroups));
  const emptyQuestionActivities = questionIds.map((x) => ({
    _id: x,
    notStarted: true,
  }))
  return (
    studentIds
      // .filter(id => groupedTestItemActivities[id])
      .map((studentId) => {
        const studentName =
          indexedNames[studentId] && indexedNames[studentId].firstName
        if (!indexedTestActivities[studentId]) {
          return {
            studentId,
            studentName,
            present: true,
            status: 'notStarted',
            maxScore,
            questionActivities: emptyQuestionActivities,
          }
        }
        const testActivity = indexedTestActivities[studentId]
        // const present = testActivity.status != ABSENT;
        // TODO: proper way to identify absent
        const present = testActivity.status != ABSENT
        // TODO: no graded status now. using submitted as a substitute for graded
        const graded = testActivity.status == SUBMITTED
        const submitted = testActivity.status == SUBMITTED
        const testActivityId = testActivity._id.toHexString()
        // const { maxScore, score } = testActivity;
        const questionActivitiesRaw = groupedQuestionActivities[studentId]

        const score =
          (questionActivitiesRaw &&
            questionActivitiesRaw.reduce(
              (e1, e2) => (e2.score || 0) + e1,
              0
            )) ||
          0
        const questionActivitiesIndexed =
          (questionActivitiesRaw &&
            _.keyBy(questionActivitiesRaw, (x) => x.qid)) ||
          {}
        const questionActivities = questionIds.map((el) => {
          const _id = el

          if (!questionActivitiesIndexed[el]) {
            return { _id, notStarted: true }
          }
          const x = questionActivitiesIndexed[el] as QuestionActivityType
          const { skipped } = x
          const { correct } = x
          const partialCorrect = x.partiallyCorrect
          const { score, maxScore } = x
          return {
            _id,
            skipped,
            correct,
            partialCorrect,
            score,
            maxScore,
            // TODO: timespent value capture in the front-end
            timespent: null,
          }
        })

        return {
          studentId,
          studentName,
          status: submitted ? 'submitted' : 'inProgress',
          present,
          graded,
          maxScore,
          score,
          testActivityId,
          questionActivities,
        }
      })
      .filter((x) => x)
  )
}
export async function getGradebookSummary(
  districtId: ObjectID,
  assignmentId: ObjectID,
  classId: ObjectID
) {
  const assignmentModel = new AssignmentModel()

  const assignment = await assignmentModel.getWithSpecificClass(
    districtId,
    assignmentId,
    classId
  )
  if (assignment.length === 0) {
    return {
      error: true,
      status: { code: 404, message: 'Assignment is not available' },
    }
  }
  if (assignment[0].class.length === 0) {
    return {
      error: true,
      status: {
        code: 404,
        message: 'Assignment not assigned to the requested class',
      },
    }
  }
  const classData =
    assignment[0].class.find(
      ({ redirect = false, addStudents = false }) => !(redirect || addStudents)
    ) || assignment[0].class[0]
  const submittedNumber = classData.inGradingNumber + classData.gradedNumber

  let total = classData.students ? classData.students.length : false
  if (total === false) {
    total = await enrollmentModel.Enrollment.collection.countDocuments({
      'group._id': classId.toHexString(),
      // TODO - check if both types needs to be sent
      type: GROUP_TYPES.CLASS,
      status: Status.ACTIVE,
      role: userRoles.STUDENT,
    })
  }
  const absentNumber =
    total - (submittedNumber + classData.inProgressNumber) || 0
  const userTestItemModel = new UserTestItemModel()
  const averageScoreResult = await userTestItemModel.getAverageScoreByAssignment(
    assignmentId
  )
  // console.log("average score", averageScoreResult);
  const averageScore =
    averageScoreResult.length > 0 ? averageScoreResult[0].averageScore : 0
  const summary = await userTestItemModel.getItemsSummary(assignmentId)
  return {
    total,
    submittedNumber,
    absentNumber,
    averageScore,
    itemsSummary: summary,
  }
}

function getRecentlyAttemptedTestActivitiesGroupByStudents(
  testActivities: TT.TestActivity[]
) {
  const groupedTestActivities = _.groupBy(testActivities, 'userId')
  return _.mapValues(groupedTestActivities, (groupedValues) =>
    groupedValues
      .sort((x, y) => {
        // for V1 migrated data, comparision on _id.getTimestamp()
        // could be incorrect sometimes, no
        if (y.v1Id && x.v1Id) {
          const yTimeStamp = new ObjectID(y.v1Id).getTimestamp()
          const xTimeStamp = new ObjectID(x.v1Id).getTimestamp()
          // @ts-ignore
          return yTimeStamp - xTimeStamp
        }
        return y._id.getTimestamp() - x._id.getTimestamp()
      })
      .map((x, ind) => ({ ...x, number: groupedValues.length - ind }))
      .slice(0, 3)
  )
}

const getOnlyLatestAttempetedTestActivity = (
  groupedTestActivities,
  isQuestionsView = false
) => {
  return Object.keys(groupedTestActivities)
    .map((studentId) => {
      const latest = groupedTestActivities[studentId].shift()
      if (
        latest &&
        latest.status === testActivityStatusConst.NOT_STARTED &&
        isQuestionsView
      ) {
        const prevUTA = groupedTestActivities[studentId][0]
        if (prevUTA) {
          return prevUTA
        }
      }
      const redirected = groupedTestActivities[studentId].find(
        (x) => !!x.redirect
      )
      if (latest && redirected) {
        latest.previouslyRedirected = true
      }
      return latest
    })
    .filter((x) => x)
}

export const filterActiveUtas = (testActivities) => {
  const activeUtas: object[] = []
  const testActivitiesByUserId = _.groupBy(testActivities, 'userId')
  // eslint-disable-next-line guard-for-in
  for (const userId in testActivitiesByUserId) {
    let activeUtaPerUser: object[] = []
    let tempActivities: object[] = []
    const activities = testActivitiesByUserId[userId]
    const utas = _.sortBy(activities, ({ _id }) => `${_id}`)
    for (const uta of utas) {
      const { isAssigned = true, isEnrolled = true } = _.omitBy(uta, _.isNil)
      if (isAssigned && isEnrolled) {
        activeUtaPerUser.push(uta)
      } else {
        tempActivities = [...activeUtaPerUser, uta]
        activeUtaPerUser = []
      }
    }
    if (activeUtaPerUser.length) {
      activeUtas.push(...activeUtaPerUser)
    } else if (tempActivities.length) {
      activeUtas.push(...tempActivities)
    }
  }
  return activeUtas
}

export const getStandardBasedReportData = async (
  test,
  currentClassId: ObjectID,
  icIds: number[],
  gradingScaleId: string
) => {
  let itemIds: ObjectId[] = []
  const isAutoSelect = test.itemGroups.find(
    ({ type }) => type === ITEM_GROUP_TYPES.AUTOSELECT
  )
  if (!_.isEmpty(isAutoSelect)) {
    const UTA = new UserTestActivityModel()
    const itemsToDeliverInGroup = await UTA.UserTestActivity.aggregate([
      { $match: { testId: test._id, groupId: currentClassId } },
      { $project: { itemsToDeliverInGroup: '$itemsToDeliverInGroup' } },
      { $unwind: { path: '$itemsToDeliverInGroup' } },
      { $unwind: { path: '$itemsToDeliverInGroup.items' } },
      { $group: { _id: '$itemsToDeliverInGroup.items' } },
    ])
    if (!_.isEmpty(itemsToDeliverInGroup)) {
      itemIds = itemsToDeliverInGroup.map(({ _id }) => _id)
    }
  }

  const standards = await testItemModel.getStandardsDataFromItems(icIds, [
    ...getItemsFromTest(test.itemGroups).map(({ itemId }) => itemId),
    ...itemIds,
  ])

  gradingScaleId = _.isEmpty(gradingScaleId)
    ? _.get(test, 'standardGradingScale._id')
    : gradingScaleId
  const scale = await standardsProficiencyModel
    .getById(gradingScaleId, {
      scale: 1,
      _id: 0,
    })
    .lean()
  return {
    standards,
    assignmentMastery: _.get(scale, 'scale', []),
  }
}

export const getActivityDataForGradeBook = async (
  assignmentId,
  groupId,
  studentIds,
  hasRandomQuestions,
  isQuestionsView = false
) => {
  const testActivityModel = new UserTestActivityModel()
  const utaQueryFilter: { [key: string]: any } = {
    assignmentId: new ObjectID(assignmentId),
    groupId: new ObjectID(groupId),
    userId: { $in: studentIds },
    languagePreferenceSwitched: { $ne: true },
  }

  let testActivities: any = await testActivityModel.UserTestActivity.find(
    utaQueryFilter,
    {
      _id: 1,
      userId: 1,
      status: 1,
      redirect: 1,
      archived: 1,
      algoVariableSetIds: 1,
      v1Id: 1,
      graded: 1,
      ...(hasRandomQuestions ? { itemsToDeliverInGroup: 1 } : {}),
      endDate: 1,
      feedback: 1,
      score: 1,
      maxScore: 1,
      isEnrolled: 1,
      isAssigned: 1,
      isPaused: 1,
      pauseReason: 1,
      tabNavigationCounter: 1,
      languagePreference: 1,
    }
  )
    .lean()
    .exec()

  testActivities = filterActiveUtas(testActivities)
  const recentTestActivitiesGrouped = getRecentlyAttemptedTestActivitiesGroupByStudents(
    testActivities as TT.TestActivity[]
  )

  testActivities = getOnlyLatestAttempetedTestActivity(
    recentTestActivitiesGrouped,
    isQuestionsView
  )

  const uqaQueryFilter: any = {
    assignmentId,
    testActivityId: {
      $in: testActivities.map((x) => x._id),
    },
    groupId: new ObjectID(groupId),
    userId: { $in: studentIds.map((x) => new ObjectID(x)) },
  }

  const testQuestionActivities = await new QuestActivityModel().QuestionActivity.find(
    uqaQueryFilter,
    {
      id: 1,
      qid: 1,
      testActivityId: 1,
      correct: 1,
      skipped: 1,
      score: 1,
      maxScore: 1,
      partiallyCorrect: 1,
      testItemId: 1,
      timeSpent: 1,
      userId: 1,
      scoringDisabled: 1,
      graded: 1,
      pendingEvaluation: 1,
      'scratchPad.scratchpad': 1,
      userResponse: 1,
      autoGrade: 1,
      isPractice: 1,
    }
  )
    .read('secondaryPreferred')
    .lean()
    .exec()

  return {
    testActivities,
    testQuestionActivities,
    recentTestActivitiesGrouped,
  }
}

export const getAssignmentDataForGradeBook = async (
  assignment,
  classId,
  teacherId,
  userRole
) => {
  const {
    testType,
    testContentVisibility,
    timedAssignment = false,
    assignedBy,
    scoringType,
    applyEBSR,
    allowTeacherRedirect,
  } = assignment
  const {
    openPolicy,
    closePolicy,
    answerOnPaper,
    passwordPolicy: classPasswordPolicy,
    passwordExpireIn,
    assignmentPassword,
    releaseScore,
  } = getClassAssignmentSettings(assignment).get(`${classId}`)
  const originalClass = assignment.class.find(
    ({ redirect = false, addStudents = false }) => !(redirect || addStudents)
  )
  const status = getClassStatusMap(assignment.class)[`${classId}`]
  const assignmentData: { [key: string]: any } = {
    status,
    releaseScore,
    answerOnPaper,
    testType,
    testContentVisibility,
    openPolicy,
    closePolicy,
    scoringType,
    applyEBSR,
    allowTeacherRedirect,
  }
  if (assignedBy) {
    if (`${assignedBy._id}` !== `${teacherId}`) {
      const user = await userModel.getById(assignedBy._id, { role: 1 })
      if (user) {
        assignmentData.assignedBy = { ...assignedBy, role: user.role }
      }
    } else {
      assignmentData.assignedBy = { ...assignedBy, role: userRole }
    }
  }
  if (closePolicy === assignmentPolicyOptions.POLICY_AUTO_ON_DUEDATE) {
    assignmentData.endDate = getMaxDateForAssignmentClass(
      'endDate',
      assignment.class
    )
  }
  if (openPolicy === assignmentPolicyOptions.POLICY_AUTO_ON_STARTDATE) {
    assignmentData.startDate = originalClass.startDate
  } else {
    assignmentData.allowedOpenDate = originalClass.allowedOpenDate || 0
    assignmentData.open = originalClass.open
    assignmentData.openDate = originalClass.openDate
  }
  assignmentData.isPaused = isAssignmentPaused(assignment.class, `${classId}`)
  assignmentData.dueDate = getMaxDateForAssignmentClass(
    'dueDate',
    assignment.class
  )
  assignmentData.detailedClasses = assignment.class.map(
    ({ _id, students, dueDate, redirectedDate }) => ({
      _id,
      students,
      dueDate,
      redirectedDate,
    })
  )
  assignmentData.timedAssignment =
    originalClass.timedAssignment || timedAssignment
  assignmentData.allowedTime =
    originalClass.allowedTime || assignment.allowedTime
  assignmentData.pauseAllowed = !!(originalClass.pauseAllowed !== undefined
    ? originalClass.pauseAllowed
    : (assignment as any).pauseAllowed)
  assignmentData.ts = Date.now()
  assignmentData.specificStudents = !!_.get(
    originalClass,
    'students.length',
    false
  )
  assignmentData.classesCanBeMarked =
    status === AssignmentStatus.IN_GRADING ? [classId] : []
  assignmentData.passwordPolicy = classPasswordPolicy
  if (classPasswordPolicy === passwordPolicy.PASSWORD_POLICY_DYNAMIC) {
    assignmentData.passwordExpireIn = passwordExpireIn
    if (originalClass.assignmentPassword) {
      assignmentData.assignmentPassword = decrypt(
        originalClass.assignmentPassword
      )
      assignmentData.passwordCreatedDate = originalClass.passwordCreatedDate
      assignmentData.passwordExpireTime = originalClass.passwordExpireTime
    }
  } else if (classPasswordPolicy === passwordPolicy.PASSWORD_POLICY_STATIC) {
    assignmentData.assignmentPassword = decrypt(assignmentPassword)
  }
  assignmentData.bulkAssignedCount = _.get(assignment, 'bulkAssignedCount', 0)
  assignmentData.bulkAssignedCountProcessed = _.get(
    assignment,
    'bulkAssignedCountProcessed',
    0
  )
  if (assignment.performanceBand) {
    assignmentData.performanceBand = assignment.performanceBand
  }
  if (assignment.standardGradingScale) {
    assignmentData.standardGradingScale = assignment.standardGradingScale
  }
  if (assignment.termId) {
    assignmentData.termId = assignment.termId
  }

  const canOpenClass: string[] = []
  const canCloseClass: string[] = []

  let isClosed
  let endDate
  if (closePolicy !== assignmentPolicyOptions.POLICY_AUTO_ON_DUEDATE) {
    isClosed =
      assignment.class.filter(
        ({ closed, endDate }) => !closed && (!endDate || endDate > Date.now())
      ).length === 0
  } else {
    endDate = getMaxDateForAssignmentClass('endDate', assignment.class)
  }
  const now = Date.now()
  if (
    status === AssignmentStatus.NOT_OPEN &&
    ((openPolicy === assignmentPolicyOptions.POLICY_AUTO_ON_STARTDATE &&
      now < originalClass.startDate) ||
      (openPolicy !== assignmentPolicyOptions.POLICY_AUTO_ON_STARTDATE &&
        !originalClass.open))
  ) {
    canOpenClass.push(classId)
  }
  if (
    (closePolicy === assignmentPolicyOptions.POLICY_AUTO_ON_DUEDATE &&
      now < endDate) ||
    (typeof isClosed !== 'undefined' && !isClosed)
  ) {
    canCloseClass.push(classId)
  }
  assignmentData.canOpenClass = canOpenClass
  assignmentData.canCloseClass = canCloseClass
  assignmentData.redirectedDates = getRedirectedDatesForAssignmentClass(
    assignment.class
  )
  assignmentData.bubbleSheetTestId = assignment.bubbleSheetTestId
  return assignmentData
}

export const getAssignmentStudentsDataForGradeBook = async (
  assignment,
  classId,
  includeInactive,
  includeStudents
) => {
  const [studentIds, , enrollmentRows] = await getStudentsAndEnrollments(
    assignment.class,
    `${classId}`,
    includeInactive,
    includeStudents
  )
  const enrolledUsersIds = enrollmentRows.map(
    ({ user }) => new ObjectId(user._id)
  )
  const totalCount = await enrollmentModel
    .getStudentsByClass(classId.toHexString(), assignment.class[0].type, {
      _id: 1,
    })
    .count()
  const enrollmentStatus = enrollmentRows.reduce((acc, cur) => {
    acc[cur.user._id] = cur.status
    return acc
  }, {})
  const userQueryFilter: any = {
    _id: { $in: studentIds.map((x) => new ObjectId(x)) },
    status: Status.ACTIVE,
  }
  if (includeInactive) {
    userQueryFilter._id = { $in: enrolledUsersIds }
    userQueryFilter.status = {
      $in: [Status.ARCHIVED, Status.ACTIVE, Status.DISABLE],
    }
  }
  const students = await userModel.User.find(userQueryFilter, {
    _id: 1,
    firstName: 1,
    lastName: 1,
    middleName: 1,
    email: 1,
    username: 1,
    tts: 1,
  })
    .lean()
    .exec()

  return { students, totalCount, enrollmentStatus }
}

export const getStudentsData = async ({
  districtId,
  assignmentId,
  classId,
  teacherId,
  pageNo,
  userRole,
  includeInactive = false,
  isQuestionsView = false,
  includeStudents,
  leftOverStudents = [],
  userId,
}): Promise<{ [key: string]: any }> => {
  const Assignment = new AssignmentModel()
  let assignment = await Assignment.getById(districtId, assignmentId).lean()

  if (!assignment) {
    return {
      error: true,
      status: { code: 404, message: 'Assignment is not available' },
    }
  }

  ;[assignment] = AssignmentModel.filterArchivedClasses([assignment])
  const allClassIds = _.uniq(assignment?.class.map((x) => `${x._id}`))
  ;[assignment] = AssignmentModel.filterByClass([assignment], '_id', [classId])

  if (!assignment || (assignment && assignment.class.length === 0)) {
    return {
      error: true,
      status: {
        code: 404,
        message: 'Assignment not assigned to the requested class',
      },
    }
  }

  const test = await testModel.getById(assignment.testId, {
    testCategory: 1,
    itemGroups: 1,
    title: 1,
    authors: 1,
  })

  if (!test) {
    return {
      error: true,
      status: { code: 404, message: 'Assignment is not available' },
    }
  }

  const hasRandomQuestions = hasRandomQuestionsInGroup(test)

  if (pageNo === 0) {
    const teacher = await userModel.getById(teacherId, {
      districtIds: 1,
      institutionIds: 1,
      currentDistrictId: 1,
    })

    if (!teacher) {
      return {
        error: true,
        status: { code: 404, message: 'teacher not found' },
      }
    }

    const currentDistrictId = getCurrentDistrictId(
      _.pick(teacher, ['districtIds', 'currentDistrictId'])
    )
    const institutionIds = await getCurrentDistrictInstitutionIds(
      teacher.institutionIds,
      districtId,
      teacher.districtIds
    )
    const interestedCurriculumIds = await getInterestedCurriculums(
      {
        districtIds: teacher.districtIds,
        institutionIds,
        currentDistrictId,
      },
      teacherId,
      false,
      true
    )

    const assignmentData = await getAssignmentDataForGradeBook(
      assignment,
      classId,
      teacherId,
      userRole
    )

    const stdBasedReportData = await getStandardBasedReportData(
      test,
      classId,
      interestedCurriculumIds,
      assignmentData.standardGradingScale?._id
    )

    const {
      students,
      totalCount,
      enrollmentStatus,
    } = await getAssignmentStudentsDataForGradeBook(
      assignment,
      classId,
      includeInactive,
      includeStudents
    )

    let classFilter: any = { _id: { $in: allClassIds } }

    if (userRole === userRoles.TEACHER) {
      classFilter = {
        ...classFilter,
        'owners.id': userId,
      }
    } else if (userRole === userRoles.SCHOOL_ADMIN) {
      const userData = await userModel
        .getById(userId, { institutionIds: 1 })
        .lean()
      classFilter = {
        ...classFilter,
        institutionId: { $in: userData.institutionIds },
      }
    }

    const classNames: {
      _id: ObjectId
      name?: string
    }[] = await groupModel.Group.find(classFilter, { _id: 1, name: 1 })
      .lean()
      .exec()

    const studentIds = students.map((x) => `${x._id}`)
    let chunk = studentIds.slice(0, 25)
    let leftOverStudents = _.difference(studentIds, chunk)
    if (leftOverStudents.length <= 10) {
      chunk = studentIds
      leftOverStudents = []
    }
    const activityData = await getActivityDataForGradeBook(
      assignmentId,
      classId,
      chunk,
      hasRandomQuestions,
      isQuestionsView
    )

    return {
      students,
      enrollmentStatus,
      leftOverStudents,
      ...activityData,
      additionalData: {
        ...assignmentData,
        ...stdBasedReportData,
        classes: classNames,
        classId,
        className: classNames.find(({ _id }) => `${_id}` === `${classId}`)
          ?.name,
        testName: test.title,
        testId: test._id,
        testAuthors: test.authors,
        totalCount,
      },
    }
  }
  let activityData = {}
  if (leftOverStudents.length) {
    activityData = await getActivityDataForGradeBook(
      assignmentId,
      classId,
      leftOverStudents,
      hasRandomQuestions,
      isQuestionsView
    )
  }
  return activityData
}

const getRedirectedDatesForAssignmentClass = (
  classlist: ClassAssignment[] = []
) => {
  return _.chain(classlist)
    .filter((el) => el.redirect === true)
    .sortBy(['redirectedDate'])
    .reduce((acc, el) => {
      if (el.students && el.students.length) {
        el.students.forEach((x) => {
          acc[`student_${x}`] = el.redirectedDate
        })
      } else {
        acc = {}
        acc[`class_${el._id}`] = el.redirectedDate
      }
      return acc
    }, {})
    .value()
}

export const publishAddItemForGradebook = async (
  questionActivities,
  assignmentId,
  groupId
) => {
  const messagePayload = questionActivities.map(
    ({
      qid,
      score,
      skipped,
      correct,
      testActivityId,
      testItemId,
      userResponse,
    }) => ({
      _id: qid,
      score,
      skipped,
      correct,
      testActivityId,
      testItemId,
      userResponse,
    })
  )
  await publish(
    `gradebook:${groupId}:${assignmentId}`,
    'addItem',
    messagePayload
  )
}

export const publishRemovedQuestionsForGradebook = async (
  removedQuestions,
  assignmentId,
  groupId
) => {
  await publish(
    `gradebook:${groupId}:${assignmentId}`,
    'removeQuestions',
    removedQuestions
  )
}

export const publishAddQuestionsMaxScoreForGradebook = async (
  qidMaxScore,
  assignmentId,
  groupId
) => {
  await publish(
    `gradebook:${groupId}:${assignmentId}`,
    'addQuestionsMaxScore',
    qidMaxScore
  )
}

export const getQuestionActivitiesOfStudentsByItemId = async (
  assignmentId,
  groupId,
  testItemId
) => {
  const testActivityModel = new UserTestActivityModel()

  const { SUBMITTED, ABSENT } = testActivityStatusConst

  const latestTestActivities = await testActivityModel.getLatestTestActivities(
    groupId,
    assignmentId,
    [],
    null,
    { _id: 1, status: 1, userId: 1 }
  )

  const notStartedUtas: any = []
  const otherUtas: any = []
  latestTestActivities.forEach((uta) => {
    if (uta.status === testActivityStatusConst.NOT_STARTED) {
      notStartedUtas.push(uta)
    } else {
      otherUtas.push(uta)
    }
  })

  const previousUta = await testActivityModel.getPreviousTestActivities(
    notStartedUtas.map(({ userId }) => userId),
    assignmentId,
    groupId,
    notStartedUtas.map(({ _id }) => _id),
    [SUBMITTED, ABSENT]
  )

  const result =
    (await new QuestActivityModel().QuestionActivity.find({
      assignmentId,
      groupId,
      testItemId,
      testActivityId: {
        $in: [
          ...previousUta.map((x) => x.testActivityId),
          ...otherUtas.map(({ _id }) => _id),
        ],
      },
    })
      .lean()
      .exec()) || []

  /**
   * @see https://snapwiz.atlassian.net/browse/EV-25831
   * Not to show evaluation highlights if question isn't automarkable
   */
  result.forEach((res) => {
    if (
      res &&
      res.autoGrade === false &&
      !res.isGradedExternally &&
      res.evaluation
    ) {
      res.evaluation = undefined
    }
  })

  return result
}
