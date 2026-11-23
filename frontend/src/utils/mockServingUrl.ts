/**
 * The one function that composes a callable mock endpoint URL (V240 inventory
 * segment — extracted from the docs page's stubUrl because the inventory page
 * needs the identical shape: reuse >=2 must be a shared util, red line ⑤).
 *
 * URL = public base + traffic prefix + '/' + routing handle + path pattern.
 * Absence is honest: without a serving base (config read failed) the caller
 * gets '' and shows the "serving root unavailable" state, never a guessed host.
 */
export interface ServingUrlParts {
  publicBaseUrl: string
  trafficBasePath: string
}

export function mockEndpointUrl(
  base: ServingUrlParts | null,
  spaceKey: string | null | undefined,
  pathPattern: string,
): string {
  if (!base || !spaceKey) return ''
  return `${base.publicBaseUrl}${base.trafficBasePath}/${spaceKey}${pathPattern}`
}

/** curl example line for one endpoint (docs page + inventory share the copy). */
export function mockEndpointCurl(method: string, url: string): string {
  return `curl -X ${method} '${url}'`
}
