import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

import type { DepthSceneSegment } from '../depth-profile/depth-path'

export interface SceneObjectDescriptor {
  readonly kind: 'surface' | 'pipe'
  readonly z: number
  readonly widthM?: number
  readonly heightM?: number
  readonly color?: string
}

export function sceneObjectDescriptors(segments: readonly DepthSceneSegment[]): readonly SceneObjectDescriptor[] {
  return [
    { kind: 'surface', z: 0 },
    ...segments.map((segment) => ({ kind: 'pipe' as const, z: segment.start.z, widthM: segment.widthM, heightM: segment.heightM, color: '#e9582f' })),
  ]
}

export interface DepthSceneAdapter {
  resetView(): void
  destroy(): void
}

function dispose(object: THREE.Object3D): void {
  object.traverse((child) => {
    const mesh = child as THREE.Mesh
    mesh.geometry?.dispose()
    const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
    materials.forEach((material) => material?.dispose())
  })
}

export function createDepthSceneAdapter(container: HTMLElement, segments: readonly DepthSceneSegment[]): DepthSceneAdapter {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(container.clientWidth || 640, container.clientHeight || 420)
  renderer.setClearColor('#e9efea')
  container.replaceChildren(renderer.domElement)
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(42, (container.clientWidth || 640) / (container.clientHeight || 420), 0.1, 5000)
  const controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  const bounds = segments.flatMap((segment) => [segment.start, segment.end])
  const size = Math.max(20, ...bounds.flatMap((point) => [Math.abs(point.x), Math.abs(point.y)]))
  const target = new THREE.Vector3(size / 2, 0, -8)
  const resetView = () => {
    camera.position.set(size * 1.15, -size * 1.15, size * 0.9)
    controls.target.copy(target)
    controls.update()
  }
  resetView()
  scene.add(new THREE.HemisphereLight('#ffffff', '#71877d', 2.1), new THREE.DirectionalLight('#ffffff', 1.4))
  const surface = new THREE.Mesh(new THREE.PlaneGeometry(size * 3, size * 3), new THREE.MeshStandardMaterial({ color: '#d8e5d4', roughness: 0.95 }))
  surface.receiveShadow = true
  scene.add(surface)
  for (const segment of segments) {
    const start = new THREE.Vector3(segment.start.x, segment.start.y, segment.start.z - segment.heightM / 2)
    const end = new THREE.Vector3(segment.end.x, segment.end.y, segment.end.z - segment.heightM / 2)
    const vector = end.clone().sub(start)
    const mesh = new THREE.Mesh(
      new THREE.BoxGeometry(vector.length(), segment.heightM, segment.widthM),
      new THREE.MeshStandardMaterial({ color: '#e9582f', roughness: 0.45 }),
    )
    mesh.position.copy(start.clone().add(end).multiplyScalar(0.5))
    mesh.quaternion.setFromUnitVectors(new THREE.Vector3(1, 0, 0), vector.normalize())
    scene.add(mesh)
  }
  let frame = 0
  const render = () => { controls.update(); renderer.render(scene, camera); frame = requestAnimationFrame(render) }
  render()
  const observer = new ResizeObserver(() => {
    const width = container.clientWidth || 640
    const height = container.clientHeight || 420
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    renderer.setSize(width, height)
  })
  observer.observe(container)
  return { resetView, destroy: () => { cancelAnimationFrame(frame); observer.disconnect(); controls.dispose(); dispose(scene); renderer.dispose(); container.replaceChildren() } }
}
