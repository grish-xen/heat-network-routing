import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

import type { DepthSceneSegment } from '../depth-profile/depth-path'

export interface SceneObjectDescriptor {
  readonly kind: 'surface' | 'pipe'
  readonly z: number
  readonly widthM?: number
  readonly heightM?: number
  readonly color?: string
  readonly opacity?: number
}

export function sceneObjectDescriptors(segments: readonly DepthSceneSegment[]): readonly SceneObjectDescriptor[] {
  return [
    { kind: 'surface', z: 0, opacity: 0.24 },
    ...segments.map((segment) => ({ kind: 'pipe' as const, z: segment.start.z, widthM: segment.widthM, heightM: segment.heightM * 4, color: '#e9582f' })),
  ]
}

export interface DepthSceneAdapter {
  resetView(): void
  destroy(): void
}

export function buildEnvelopeGeometry(segment: DepthSceneSegment): THREE.BufferGeometry {
  const height = segment.heightM * 4
  const dx = segment.end.x - segment.start.x
  const dy = segment.end.y - segment.start.y
  const horizontalLength = Math.hypot(dx, dy) || 1
  const offsetX = (-dy / horizontalLength) * segment.widthM / 2
  const offsetY = (dx / horizontalLength) * segment.widthM / 2
  const point = (x: number, y: number, z: number) => [x, y, z] as const
  const topStartLeft = point(segment.start.x + offsetX, segment.start.y + offsetY, segment.start.z)
  const topStartRight = point(segment.start.x - offsetX, segment.start.y - offsetY, segment.start.z)
  const topEndLeft = point(segment.end.x + offsetX, segment.end.y + offsetY, segment.end.z)
  const topEndRight = point(segment.end.x - offsetX, segment.end.y - offsetY, segment.end.z)
  const vertices = [topStartLeft, topStartRight, point(topStartLeft[0], topStartLeft[1], topStartLeft[2] - height), point(topStartRight[0], topStartRight[1], topStartRight[2] - height), topEndLeft, topEndRight, point(topEndLeft[0], topEndLeft[1], topEndLeft[2] - height), point(topEndRight[0], topEndRight[1], topEndRight[2] - height)]
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices.flat(), 3))
  geometry.setIndex([0, 1, 3, 0, 3, 2, 4, 6, 7, 4, 7, 5, 0, 4, 5, 0, 5, 1, 2, 3, 7, 2, 7, 6, 0, 2, 6, 0, 6, 4, 1, 5, 7, 1, 7, 3])
  geometry.computeVertexNormals()
  return geometry
}

export function cameraFrame(points: readonly { readonly x: number; readonly y: number; readonly z: number }[], aspect: number) {
  const box = new THREE.Box3().setFromPoints(points.map((point) => new THREE.Vector3(point.x, point.y, point.z)))
  const target = box.getCenter(new THREE.Vector3())
  const size = box.getSize(new THREE.Vector3())
  const radius = Math.max(size.x / Math.max(aspect, 0.1), size.y, size.z, 20) / 2
  const distance = radius / Math.tan(THREE.MathUtils.degToRad(42) / 2) * 1.35
  return { target, position: target.clone().add(new THREE.Vector3(0, -distance, distance)) }
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
  const initialFrame = cameraFrame(bounds, camera.aspect)
  const size = Math.max(20, ...bounds.flatMap((point) => [Math.abs(point.x), Math.abs(point.y)]))
  const resetView = () => {
    camera.position.copy(initialFrame.position)
    controls.target.copy(initialFrame.target)
    controls.update()
  }
  resetView()
  scene.add(new THREE.HemisphereLight('#ffffff', '#71877d', 2.1), new THREE.DirectionalLight('#ffffff', 1.4))
  const surface = new THREE.Mesh(new THREE.PlaneGeometry(size * 3, size * 3), new THREE.MeshStandardMaterial({ color: '#d8e5d4', roughness: 0.95, transparent: true, opacity: 0.24, depthWrite: false }))
  surface.receiveShadow = true
  scene.add(surface)
  for (const segment of segments) {
    const mesh = new THREE.Mesh(
      buildEnvelopeGeometry(segment),
      new THREE.MeshStandardMaterial({ color: '#e9582f', roughness: 0.45 }),
    )
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
