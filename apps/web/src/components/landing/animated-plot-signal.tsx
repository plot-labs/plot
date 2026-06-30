"use client";

import { useEffect, useRef } from "react";
import * as THREE from "three";

function roundedRectShape(width: number, height: number, radius: number) {
  const x = -width / 2;
  const y = -height / 2;
  const shape = new THREE.Shape();

  shape.moveTo(x + radius, y);
  shape.lineTo(x + width - radius, y);
  shape.quadraticCurveTo(x + width, y, x + width, y + radius);
  shape.lineTo(x + width, y + height - radius);
  shape.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
  shape.lineTo(x + radius, y + height);
  shape.quadraticCurveTo(x, y + height, x, y + height - radius);
  shape.lineTo(x, y + radius);
  shape.quadraticCurveTo(x, y, x + radius, y);

  return shape;
}

function createBlockTexture(label: string) {
  const canvas = document.createElement("canvas");
  canvas.width = 256;
  canvas.height = 180;
  const ctx = canvas.getContext("2d");

  if (ctx) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "rgba(250,249,246,0.76)";
    ctx.font = "600 18px ui-monospace, SFMono-Regular, Menlo, monospace";
    ctx.fillText(label.toUpperCase(), 28, 42);

    ctx.fillStyle = "rgba(250,249,246,0.18)";
    ctx.fillRect(28, 72, 176, 10);
    ctx.fillRect(28, 96, 136, 10);
    ctx.fillRect(28, 120, 96, 10);

    ctx.fillStyle = "rgba(250,249,246,0.5)";
    ctx.beginPath();
    ctx.arc(218, 36, 6, 0, Math.PI * 2);
    ctx.fill();
  }

  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function createBlockCard(label: string) {
  const group = new THREE.Group();
  const depth = 0.055;
  const geometry = new THREE.ExtrudeGeometry(roundedRectShape(0.82, 0.58, 0.08), {
    bevelEnabled: true,
    bevelSegments: 5,
    bevelSize: 0.022,
    bevelThickness: 0.022,
    depth,
  });
  geometry.center();

  const material = new THREE.MeshStandardMaterial({
    color: 0x151513,
    metalness: 0.12,
    roughness: 0.62,
  });
  const card = new THREE.Mesh(geometry, material);
  card.castShadow = true;
  card.receiveShadow = true;
  group.add(card);

  const texture = createBlockTexture(label);
  const labelMaterial = new THREE.MeshBasicMaterial({
    map: texture,
    transparent: true,
    opacity: 0.95,
  });
  const labelPlane = new THREE.Mesh(new THREE.PlaneGeometry(0.68, 0.48), labelMaterial);
  labelPlane.position.z = depth / 2 + 0.018;
  group.add(labelPlane);

  return group;
}

type OrbitDot = {
  dot: THREE.Mesh;
  orbit: THREE.Group;
  radius: number;
  phase: number;
  speed: number;
};

export function AnimatedPlotSignal() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const frameRef = useRef<number | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const renderer = new THREE.WebGLRenderer({
      alpha: true,
      antialias: true,
      canvas,
    });
    renderer.setClearColor(0x000000, 0);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(36, 1, 0.1, 100);
    camera.position.set(0, 0.08, 5.4);
    camera.lookAt(0, 0, 0);

    const root = new THREE.Group();
    root.rotation.x = -0.18;
    root.rotation.y = 0.18;
    scene.add(root);

    scene.add(new THREE.AmbientLight(0xffffff, 1.8));
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.6);
    keyLight.position.set(-2.4, 3.2, 4.8);
    scene.add(keyLight);

    const fillLight = new THREE.DirectionalLight(0xfaf9f6, 0.9);
    fillLight.position.set(2.2, -1.2, 3.5);
    scene.add(fillLight);

    const orbitMaterial = new THREE.MeshBasicMaterial({
      color: 0x12110f,
      opacity: 0.16,
      transparent: true,
    });
    const ringTilts = [
      { radius: 1.35, x: 1.08, y: 0.08, z: -0.12 },
      { radius: 1.72, x: 1.2, y: -0.1, z: 0.16 },
      { radius: 2.05, x: 1.0, y: 0.2, z: 0.04 },
    ];

    ringTilts.forEach((ring) => {
      const mesh = new THREE.Mesh(
        new THREE.TorusGeometry(ring.radius, 0.006, 8, 160),
        orbitMaterial.clone(),
      );
      mesh.rotation.set(ring.x, ring.y, ring.z);
      root.add(mesh);
    });

    const textureLoader = new THREE.TextureLoader();
    const plotTexture = textureLoader.load("/plot-icon.svg");
    plotTexture.colorSpace = THREE.SRGBColorSpace;

    const plotMark = new THREE.Mesh(
      new THREE.PlaneGeometry(0.58, 0.58),
      new THREE.MeshBasicMaterial({
        map: plotTexture,
        transparent: true,
      }),
    );
    plotMark.position.z = 0.052;
    root.add(plotMark);

    const cardGroup = new THREE.Group();
    root.add(cardGroup);

    const cards = [
      { label: "source", position: [-1.18, 0.64, -0.16], rotation: [-0.12, 0.28, -0.22] },
      { label: "signal", position: [1.12, 0.52, -0.08], rotation: [0.08, -0.34, 0.2] },
      { label: "voice", position: [-0.96, -0.72, 0.06], rotation: [0.1, 0.2, 0.12] },
      { label: "brief", position: [0.98, -0.66, -0.02], rotation: [-0.08, -0.18, -0.16] },
    ].map((config) => {
      const card = createBlockCard(config.label);
      card.position.set(config.position[0], config.position[1], config.position[2]);
      card.rotation.set(config.rotation[0], config.rotation[1], config.rotation[2]);
      cardGroup.add(card);
      return card;
    });
    const cardBaseY = cards.map((card) => card.position.y);

    const dots: OrbitDot[] = ringTilts.flatMap((ring, ringIndex) => {
      const orbit = new THREE.Group();
      orbit.rotation.set(ring.x, ring.y, ring.z);
      root.add(orbit);

      return [0, 1].map((dotIndex) => {
        const dot = new THREE.Mesh(
          new THREE.SphereGeometry(dotIndex === 0 ? 0.04 : 0.028, 18, 18),
          new THREE.MeshBasicMaterial({
            color: dotIndex === 0 ? 0x12110f : 0x6f6c65,
            transparent: true,
            opacity: dotIndex === 0 ? 0.72 : 0.45,
          }),
        );
        orbit.add(dot);
        return {
          dot,
          orbit,
          radius: ring.radius,
          phase: ringIndex * 1.7 + dotIndex * Math.PI,
          speed: 0.24 + ringIndex * 0.04 + dotIndex * 0.02,
        };
      });
    });

    const resizeRendererToDisplaySize = () => {
      const { clientWidth, clientHeight } = renderer.domElement;
      const needsResize =
        renderer.domElement.width !== Math.floor(clientWidth * renderer.getPixelRatio()) ||
        renderer.domElement.height !== Math.floor(clientHeight * renderer.getPixelRatio());

      if (needsResize) {
        renderer.setSize(clientWidth, clientHeight, false);
        camera.aspect = clientWidth / Math.max(clientHeight, 1);
        camera.updateProjectionMatrix();
      }
    };

    const animate = (now: number) => {
      const time = now * 0.001;
      resizeRendererToDisplaySize();

      root.rotation.y = 0.18 + Math.sin(time * 0.38) * 0.12;
      root.rotation.x = -0.18 + Math.sin(time * 0.31) * 0.045;
      cardGroup.rotation.z = Math.sin(time * 0.28) * 0.05;

      cards.forEach((card, index) => {
        card.position.y = cardBaseY[index] + Math.sin(time * 1.2 + index) * 0.035;
      });

      dots.forEach(({ dot, radius, phase, speed }) => {
        const angle = phase + time * speed;
        dot.position.set(Math.cos(angle) * radius, Math.sin(angle) * radius, 0);
      });

      renderer.render(scene, camera);
      frameRef.current = requestAnimationFrame(animate);
    };

    frameRef.current = requestAnimationFrame(animate);
    window.addEventListener("resize", resizeRendererToDisplaySize);

    return () => {
      if (frameRef.current) cancelAnimationFrame(frameRef.current);
      window.removeEventListener("resize", resizeRendererToDisplaySize);
      plotTexture.dispose();
      scene.traverse((object) => {
        if (object instanceof THREE.Mesh) {
          object.geometry.dispose();
          const materials = Array.isArray(object.material)
            ? object.material
            : [object.material];
          materials.forEach((material) => {
            Object.values(material).forEach((value) => {
              if (value instanceof THREE.Texture) value.dispose();
            });
            material.dispose();
          });
        }
      });
      renderer.dispose();
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="h-full w-full"
      style={{ display: "block" }}
    />
  );
}
