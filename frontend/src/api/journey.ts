import { request } from './request'

/**
 * Journey orchestration API — mirror of the admin bridge /api/v1/journeys/**
 * (core journey domain). A journey plan lives under a program (project set);
 * its ordered segments reference published shared assets or project cases,
 * and a run expands every segment into the batch of that segment's owning
 * project (H1 project scoping never moves to the program level).
 * Field names mirror the admin DTO records one-to-one.
 */

/** Run + run-segment status single vocabulary: 0 pending / 1 running / 2 succeeded / 3 failed / 4 skipped. */
export type JourneyRunStatus = 0 | 1 | 2 | 3 | 4

export interface JourneySegmentView {
  id: number
  seqNo: number
  projectId: number
  targetKind: 'asset' | 'case'
  assetRowId: number | null
  caseId: number | null
  paramsOverrideJson: string | null
}

export interface JourneyView {
  id: number
  programId: number
  name: string
  ownerUserId: number
  /** 1 = active, 2 = archived (tp_journey.status) */
  status: number
  /** Header-only on the paged wall endpoint (empty list); only GET /{id} fills it. */
  segments: JourneySegmentView[]
}

/** Paged wall envelope from admin JourneyPageView (total = whole filtered count, not the page's rows). */
export interface JourneyPageView {
  list: JourneyView[]
  total: number
  page: number
  pageSize: number
}

export interface JourneyRunSegmentView {
  id: number
  segmentId: number
  seqNo: number
  projectId: number
  batchId: number | null
  status: JourneyRunStatus
  /** PARAMS_MISSING / LAUNCH_FAILED / batch terminal failure — names the reason for the traffic light. */
  failureCode: string | null
  failureMessage: string | null
}

export interface JourneyRunView {
  id: number
  journeyId: number
  status: JourneyRunStatus
  signedBy: number | null
  signedAt: string | null
  segments: JourneyRunSegmentView[]
}

export interface JourneySegmentForm {
  seqNo: number
  projectId: number
  targetKind: 'asset' | 'case'
  assetRowId?: number | null
  caseId?: number | null
  paramsOverrideJson?: string | null
}

export function createJourney(form: { programId: number; name: string; ownerUserId: number }): Promise<number> {
  return request({ url: '/journeys', method: 'post', data: form })
}

export function addJourneySegment(journeyId: number, form: JourneySegmentForm): Promise<JourneySegmentView> {
  return request({ url: `/journeys/${journeyId}/segments`, method: 'post', data: form })
}

export function getJourney(id: number): Promise<JourneyView> {
  return request({ url: `/journeys/${id}`, method: 'get' })
}

/** Paged journey wall under one program (S-group worst-scale contract; keyword = name contains, judged in core). */
export function pageJourneys(params: {
  programId: number
  page?: number
  pageSize?: number
  keyword?: string
}): Promise<JourneyPageView> {
  return request({ url: '/journeys/page', method: 'get', params })
}

/** Start a run: core expands the ordered segments into each owning project's batch, one transaction per segment. */
export function startJourneyRun(journeyId: number): Promise<JourneyRunView> {
  return request({ url: `/journeys/${journeyId}/runs`, method: 'post' })
}

export function getJourneyRun(runId: number): Promise<JourneyRunView> {
  return request({ url: `/journeys/runs/${runId}`, method: 'get' })
}

/** Exit sign-off: server enforces journey-owner-only + terminal + unsigned; rejections name the reason. */
export function signOffJourneyRun(runId: number): Promise<JourneyRunView> {
  return request({ url: `/journeys/runs/${runId}/signoff`, method: 'post' })
}
