
import { listAppautoDevices, listDeviceLeaseRuns, type AppautoDevice, type AppautoLeaseRun } from '@/api/appauto'

export interface OccupancySnapshot {
  devices: AppautoDevice[]
  
  activeLeases: AppautoLeaseRun[]
  
  loadFailed: boolean
}

export async function loadOccupancySnapshot(projectId: number): Promise<OccupancySnapshot> {
  try {
    const devices = await listAppautoDevices(projectId)
    const runs = await Promise.allSettled(devices.map((d) => listDeviceLeaseRuns(d.id)))
    const activeLeases = runs.flatMap((r) =>
      r.status === 'fulfilled' ? r.value.filter((x) => x.state === 'ACTIVE') : [])
    return { devices, activeLeases, loadFailed: false }
  } catch {
    return { devices: [], activeLeases: [], loadFailed: true }
  }
}
