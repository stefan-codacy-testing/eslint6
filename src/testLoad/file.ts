import { SUBSCRIPTION_TYPES } from '../models/orgSubscription';
export { default as httpMessages } from './httpMessages';
export { default as testItemActivityStatus } from './testItemActivityStatus';
export { default as contentSharing } from './contentSharing';
export { default as userContext } from './userContext';
export { default as collectionNames } from './itemBank';

export const Status = {
  ARCHIVED: 0,
  ACTIVE: 1,
  DISABLE: 2,
}

export const addMultipleStudentsType = {
  FIRST_NAME_LAST_NAME_USER_FORMAT: 'fl',
  LAST_NAME_FIRST_NAME_USER_FORMAT: 'lf',
  GOOGLE_USER_FORMAT: 'google',
  MSO_USER_FORMAT: 'mso',
}

export const userRoles = {
  EDULASTIC_ADMIN: 'edulastic-admin',
  DISTRICT_ADMIN: 'district-admin',
  SCHOOL_ADMIN: 'school-admin',
  TEACHER: 'teacher',
  STUDENT: 'student',
  PARENT: 'parent',
  EDULASTIC_CURATOR: 'edulastic-curator',
}

export const adminRoles = {
  DISTRICT_ADMIN: 'district-admin',
  SCHOOL_ADMIN: 'school-admin',
}

export const roles = {
  ADMIN: 'admin',
  ...userRoles,
}

export const external_roles = {
  ADMIN: 'administrator',
}

export const subscriptionType = SUBSCRIPTION_TYPES

export const ssoProvider = {
  GOOGLE: 'google',
  MSO_365: 'mso',
  CLEVER: 'clever',
  CLASSLINK: 'classlink',
  SCHOOLOGY: 'schoology',
  NEWSELA: 'newsela',
}

export const rosterProvider = {
  GOOGLE: 'google',
  CLEVER: 'clever',
  CLASSLINK: 'classlink',
  SCHOOLOGY: 'schoology',
  CANVAS: 'canvas',
}

export const customDomain = {
  STUDENT_ASSIGNMENT: 'student-assignment',
  TEACHER_PREVIEW: 'teacher-preview',
  TEACHER_REPORT: 'teacher-report',
}

export const proficiencyCalcType = {
  MOST_RECENT: 'MOST_RECENT',
  MAX_SCORE: 'MAX_SCORE',
  MODE_SCORE: 'MODE_SCORE',
  AVERAGE: 'AVERAGE',
  DECAYING_AVERAGE: 'DECAYING_AVERAGE',
  MOVING_AVERAGE: 'MOVING_AVERAGE',
  POWER_LAW: 'POWER_LAW',
}
// eslint-disable-next-line no-useless-escape
export const EMAIL_PATTERN_FORMAT = /^[_A-Za-z0-9-'\+]+(\.[_A-Za-z0-9-']+)*@[A-Za-z0-9]+([A-Za-z0-9\-\.]+)*(\.[A-Za-z]{1,25})$/

export const V1_DEMO_DISTRICT_ID = 44852

export const V1_HOME_SCHOOL_DISTRICT_TYPE = 2 // We have type = 2 for home school districts.

export const FORGOT_PASSWORD_TEMPLATE = 'asforgotpasswordtemplate'
export const FORGOT_PASSWORD_TEACHER_TEMPLATE = 'forgotpasswordteachertemplate'
export const INVITE_TEACHER_TEMPLATE = 'inviteteachertemplate'
export const SUBSCRIPTION_ABOUT_TO_EXPIRE_NOTIFICATION_TEMPLATE =
  'subscriptionabouttoexpirenotification'
export const SUBSCRIPTION_EXPIRED_NOTIFICATION_TEMPLATE =
  'subscriptionexpirednotification'

export const TRIAL_ABOUT_TO_EXPIRE_EMAIL_TEMPLATE =
  'freetrialabouttoexpirenotification'
export const TRIAL_EXPIRED_EMAIL_TEMPLATE = 'freetrialexpirednotification'
export const ALPHABETS = 'abcdefghijklmnopqrstuvwxyz'.split('')

export const activityType = {
  LOGIN: 'LOGIN',
  ASSIGNMENT: 'ASSIGNMENT',
  LOGOUT: 'LOGOUT',
  PLAYGROUND: 'PLAYGROUND',
  PROXY: 'PROXY',
  WRONG_LOGIN_ATTEMPT: 'WRONG_PASSWORD_ATTEMPT',
  BULK_DOWNLOAD: 'BULK_DOWNLOAD',
}

export const activityAction = {
  ASSIGNED: 'ASSIGNED',
  COMPLETED: 'COMPLETED',
  STARTED: 'STARTED',
  GRADES_RESPONSE_DOWNLOAD: 'GRADES_RESPONSE_DOWNLOAD',
  REPORT_CARD: 'STUDENT_REPORT_CARD',
}

export const language = {
  SPANISH: 'spanish',
  ENGLISH: 'english',
  LANGUAGE_EN: 'en',
  LANGUAGE_ES: 'es',
}

export const questionTitle = {
  SENTENCE_RESPONSE: 'Sentence Response',
  MATCH_TABLE_LABELS: 'Match Table - Labels',
}

export const multiDistrictErrorStatus = {
  RESOLVED: 'resolved',
  UNRESOLVED: 'unresolved',
}

export const SIX_HOURS_IN_MILLISECONDS = 21600000
export const ONE_DAY_IN_MILLISECONDS = 86400000
export const ONE_HOURS_IN_MILLISECONDS = 3600000
export const ONE_MINUTES_IN_MILLISECONDS = 60000
export const ONE_SECONDS_IN_MILLISECONDS = 1000

export const BASE_10 = 10 // redix

export const TOTAL_HOURS_IN_TWELVE_HOURS_FORMAT = 12 // 12 hour format

export const ENGLISH_US_LANGUAGE_CODE = 'en-US'

export const ANTE_MERIDIEM = 'AM'
export const POST_MERIDIEM = 'PM'

export const Mb8 = 8000000 // bytes
export const eeaCountryCodeList = [
  'GR',
  'GB',
  'BE',
  'ES',
  'HU',
  'SK',
  'BG',
  'FR',
  'MT',
  'FI',
  'CZ',
  'HR',
  'NL',
  'SE',
  'DK',
  'IT',
  'AT',
  'DE',
  'CY',
  'PL',
  'IS',
  'EE',
  'LV',
  'PT',
  'LI',
  'IE',
  'LT',
  'RO',
  'NO',
  'EL',
  'LU',
  'SI',
  'CH',
  'UK',
]

export const SYNC_STATUS = {
  IN_PROGRESS: 1,
  COMPLETED: 2,
  FAILED: 3,
}

export const SYNC_TYPE = {
  FULL: 'full-sync',
  DELTA: 'delta-sync',
  ACCOMMODATION: 'accommodation-sync',
  INACTIVE_GROUP: 'inactive-group',
  INACTIVE_USER: 'inactive-user',
}

export const ROSTER_ENTITY_TYPES = {
  ACADEMIC_SESSIONS: 'academicSessions',
  CLASSES: 'classes',
  COURSES: 'courses',
  DEMOGRAPHICS: 'demographics',
  ENROLLMENTS: 'enrollments',
  ORGS: 'orgs',
  USERS: 'users',
  ACCOMMODATIONS: 'accommodations',
}

export const ROSTER_SQS_SYNC_TYPES = {
  DISTRICT: 'sync-oneroster-district',
  DELTA: 'delta-sync-event-process',
  COURSES: 'sync-oneroster-courses',
  SCHOOLS: 'sync-oneroster-schools',
  SCHOOL: 'sync-oneroster-school',
  CLASS: 'sync-oneroster-class',
  ACCOMMODATIONS: 'sync-oneroster-accommodations',
  PROCESS_INACTIVE_GROUP: 'process-inactive-group',
  PROCESS_INACTIVE_USER: 'process-inactive-user',
  PROCESS_USER_GROUP_ROSTER_STATUS: 'process-user-group-roster-status',
  FETCH_ORPHAN_USERS: 'fetch-orphan-users',
  PROCESS_ORPHAN_USERS: 'process-orphan-users',
}

export const ROSTER_SYNC = 'ROSTER_SYNC'

export const EMPTY_REQ_OBJ = {}
export const EMPTY_ERROR_OBJ = {}

export const API_TRACE_LOGGER_TYPE = {
  ERROR: 'error',
  INFO: 'info',
  WARNING: 'warn',
}

export const LOGIN_PATCH = '/login'

export const bytesIn1MbBase10 = '1000000'