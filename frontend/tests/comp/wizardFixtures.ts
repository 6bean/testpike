/**
 * Single source for the mock stub wizard component-test fixtures.
 *
 * Several wizard specs mount the same component against the same contract
 * row and draft row; keeping those literals in one module prevents the
 * drift R40 warns about (a fixture diverging between two consumers silently
 * changes what one of them tests). Callers that need a variant spread over
 * the returned object instead of copying the whole literal.
 */

// A listed contract with one endpoint, matching the spec row the wizard's
// step-1 table expects.
export const wizardSpecRow = {
  id: 21,
  name: 'orders-contract',
  specVersion: 3,
  specFormat: 'OPENAPI3',
  endpoints: [{ method: 'GET', path: '/orders' }],
}

/**
 * One advised draft row for the selection step.
 *
 * The `defaultChecked` flag matters: the selection step starts from the
 * advice-recommended set (core flags advised rows), so a fixture without it
 * leaves the set empty, the import door honestly no-ops, and any assertion
 * after that click silently tests nothing.
 */
export const wizardDraftRow = {
  endpointKey: 'GET /orders',
  name: 'list orders',
  method: 'GET',
  pathMatchType: 'exact',
  pathPattern: '/orders',
  matchersJson: '{}',
  responseJson: '{}',
  source: 'spec',
  defaultChecked: true,
}
