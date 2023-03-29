import faker from 'faker'
import {
  authorDifficulties,
  depthOfKnowledges,
  QuestionType,
  questionTypes,
} from './constants'
import { generateSampleTestItemData } from './utils/testItemHelper'
import app from '../src/app'
import chai, { auth, expect } from '../test/config'

// multiplied by 1000
const numberOfRecords: number = 100

const generateNewTestItem = (questions: number) => {
  const widgets: any[] = []
  for (let i = 0; i < questions; i++) {
    const type: QuestionType = faker.random.arrayElement(questionTypes)
    const id: string = faker.random.uuid()
    const title: string = faker.random.word()
    const stimulus: string = faker.random.words()
    const depthOfKnowledge: string = faker.random.arrayElement(
      depthOfKnowledges
    )
    const authorDifficulty: string = faker.random.arrayElement(
      authorDifficulties
    )
    const otherFields = generateSampleTestItemData(type)

    const newQuestion = {
      widgetType: 'question',
      type,
      title: type,
      entity: {
        id,
        title,
        type,
        stimulus,
        ui_style: { type: 'horizontal' },
        multiple_responses: false,
        smallSize: true,
        depthOfKnowledge,
        authorDifficulty,
        ...otherFields,
      },
      tabIndex: 0,
    }
    widgets.push(newQuestion)
  }

  return {
    rows: [
      {
        tabs: [],
        dimension: '100%',
        widgets,
      },
    ],
    columns: [],
  }
}

for (let j = 0; j < numberOfRecords; j++) {
  describe(`Load Testing ${j}`, () => {
    it('should create 1000 TestItems', async () => {
      for (let i = 0; i < 1000; i++) {
        const newTestItemData = generateNewTestItem((numberOfRecords % 3) 
+ 1)
        const res = await chai
          .request(app)
          .post('/api/testitem')
          .set('Authorization', auth)
          .send(newTestItemData)
        expect(res.status, 'Create Test Item').to.equal(200)
        const testItemId = res.body.result._id
        if (i % 100 === 0) {
          console.log('100 items inserted, last testItemId', testItemId)
        }
      }
    })
  })
}
