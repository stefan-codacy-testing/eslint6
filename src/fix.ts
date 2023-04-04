import { ObjectID } from 'mongodb'
import { Router } from 'express'
import _ from 'lodash'
import {
  fixTestsAnchorStandardGrades,
  updateItemGroupAndSummaryOfTest,
} from '../controllers/Test'
import Test from '../models/test'
import { generalHandle, successHandler } from '../utils/responseHandler'
import Assignment from '../models/assignments'
import UserTestActivity from '../models/userTestActivity'
import { fixUtaForSummaryIssue } from '../controllers/TestActivity'
import { status as UTAStatus } from '../const/testActivity'

const router = Router()

router.get('/test-anchor-grades', async (req, res) => {
  try {
    await fixTestsAnchorStandardGrades()

    res.json({ msg: 'successfully finished' })
  } catch (error) {
    res.json({ error })
    console.error('error we got:', error)
  }
})

router.post('/fix-test-summary/:testId', async (req, res, next) => {
  try {
    const testId = `${req.params.testId}`
    const testModel = new Test()
    const hasWrongData = await testModel.hasWrongSummaryData(testId)
    if (!hasWrongData) {
      return generalHandle(
        res,
        `Test has valid data and can't be modified`,
        403
      )
    }
    await updateItemGroupAndSummaryOfTest(testId, req.user._id)
    const assignmentModel = new Assignment()
    let assignments = await assignmentModel.getAssignmentsByTestId(testId)
    assignments = Assignment.filterArchivedClasses(assignments)
    if (!(assignments && assignments.length)) {
      return generalHandle(res, `No Assignments linked to the test`, 200)
    }
    const test = await testModel.getById(new ObjectID(testId))
    for (const assignment of assignments) {
      if (!(assignment && assignment.class && assignment.class.length)) {
        continue
      }
      const groupIds = _.uniq(assignment.class.map((c) => c._id))
      const utas = await new UserTestActivity().getByFields({
        groupId: { $in: groupIds },
        assignmentId: assignment._id,
        status: UTAStatus.SUBMITTED,
      })
      await fixUtaForSummaryIssue({
        activities: utas,
        test,
      })
    }
    return successHandler(res, `Data issue is fixed for test ${testId}`)
  } catch (e) {
    next(e)
  }
})

export default router