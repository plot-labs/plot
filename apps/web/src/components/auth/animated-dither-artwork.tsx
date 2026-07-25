"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";

const SOURCE_IMAGE = "/images/plot-login-source-v1.webp";
const FALLBACK_IMAGE = "/images/plot-login-dither-v2.webp";
const BRAND_MARK = "/plot-logo.png";
const SHADER_SOURCE = "/shaders/dither-image.glsl";

const VERTEX_SHADER = `
  attribute vec2 a_position;

  void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
  }
`;

function compileShader(
  gl: WebGLRenderingContext,
  type: number,
  source: string,
) {
  const shader = gl.createShader(type);
  if (!shader) {
    throw new Error("Unable to create WebGL shader");
  }

  gl.shaderSource(shader, source);
  gl.compileShader(shader);

  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const message = gl.getShaderInfoLog(shader) ?? "Unknown shader error";
    gl.deleteShader(shader);
    throw new Error(message);
  }

  return shader;
}

function createProgram(gl: WebGLRenderingContext, fragmentSource: string) {
  const vertexShader = compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER);
  const fragmentShader = compileShader(
    gl,
    gl.FRAGMENT_SHADER,
    fragmentSource,
  );
  const program = gl.createProgram();

  if (!program) {
    gl.deleteShader(vertexShader);
    gl.deleteShader(fragmentShader);
    throw new Error("Unable to create WebGL program");
  }

  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  gl.deleteShader(vertexShader);
  gl.deleteShader(fragmentShader);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const message = gl.getProgramInfoLog(program) ?? "Unknown program error";
    gl.deleteProgram(program);
    throw new Error(message);
  }

  return program;
}

function loadImage(src: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new window.Image();
    image.decoding = "async";
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`Unable to load ${src}`));
    image.src = src;
  });
}

function adaptPencilShaderForWebGl(source: string) {
  return source;
}

type AnimatedDitherArtworkProps = {
  className?: string;
};

export function AnimatedDitherArtwork({
  className = "",
}: AnimatedDitherArtworkProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    const reduceMotion = window.matchMedia(
      "(prefers-reduced-motion: reduce)",
    ).matches;

    if (!canvas || reduceMotion) {
      return;
    }

    const gl = canvas.getContext("webgl", {
      alpha: false,
      antialias: false,
      powerPreference: "high-performance",
    });

    if (!gl) {
      return;
    }

    const abortController = new AbortController();
    let disposed = false;
    let frameId: number | null = null;
    let resizeObserver: ResizeObserver | null = null;
    let program: WebGLProgram | null = null;
    let texture: WebGLTexture | null = null;
    let buffer: WebGLBuffer | null = null;

    function stopAnimation() {
      if (frameId !== null) {
        cancelAnimationFrame(frameId);
        frameId = null;
      }
    }

    function handleContextLost(event: Event) {
      event.preventDefault();
      stopAnimation();
      setReady(false);
    }

    canvas.addEventListener("webglcontextlost", handleContextLost);

    async function setup(
      gl: WebGLRenderingContext,
      canvas: HTMLCanvasElement,
    ) {
      const [shaderResponse, sourceImage, brandMark] = await Promise.all([
        fetch(SHADER_SOURCE, { signal: abortController.signal }),
        loadImage(SOURCE_IMAGE),
        loadImage(BRAND_MARK),
      ]);

      if (!shaderResponse.ok) {
        throw new Error("Unable to load dither shader");
      }

      const fragmentSource = adaptPencilShaderForWebGl(
        await shaderResponse.text(),
      );
      const sourceCanvas = document.createElement("canvas");
      const sourceContext = sourceCanvas.getContext("2d");

      sourceCanvas.width = sourceImage.naturalWidth;
      sourceCanvas.height = sourceImage.naturalHeight;

      if (!sourceContext) {
        throw new Error("Unable to prepare dither source image");
      }

      sourceContext.drawImage(sourceImage, 0, 0);
      const markSize = sourceImage.naturalWidth * 0.22;
      const markX = sourceImage.naturalWidth * 0.67;
      const markY = sourceImage.naturalHeight * 0.2;
      const markCenterX = markX + markSize / 2;
      const markCenterY = markY + markSize / 2;
      const halo = sourceContext.createRadialGradient(
        markCenterX,
        markCenterY,
        markSize * 0.12,
        markCenterX,
        markCenterY,
        markSize * 0.88,
      );

      halo.addColorStop(0, "rgba(23, 21, 18, 0.5)");
      halo.addColorStop(0.58, "rgba(23, 21, 18, 0.28)");
      halo.addColorStop(1, "rgba(23, 21, 18, 0)");
      sourceContext.fillStyle = halo;
      sourceContext.fillRect(
        markCenterX - markSize,
        markCenterY - markSize,
        markSize * 2,
        markSize * 2,
      );

      const markCanvas = document.createElement("canvas");
      const markContext = markCanvas.getContext("2d");
      markCanvas.width = Math.ceil(markSize);
      markCanvas.height = Math.ceil(markSize);

      if (!markContext) {
        throw new Error("Unable to prepare Plot mark");
      }

      markContext.drawImage(brandMark, 0, 0, markSize, markSize);
      markContext.globalCompositeOperation = "source-in";
      markContext.fillStyle = "#f4efe0";
      markContext.fillRect(0, 0, markSize, markSize);
      sourceContext.drawImage(
        markCanvas,
        markX,
        markY,
        markSize,
        markSize,
      );

      if (disposed) {
        return;
      }

      program = createProgram(gl, fragmentSource);
      gl.useProgram(program);

      buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(
        gl.ARRAY_BUFFER,
        new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
        gl.STATIC_DRAW,
      );

      const position = gl.getAttribLocation(program, "a_position");
      gl.enableVertexAttribArray(position);
      gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);

      texture = gl.createTexture();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.RGBA,
        gl.RGBA,
        gl.UNSIGNED_BYTE,
        sourceCanvas,
      );

      const uniform = (name: string) =>
        gl.getUniformLocation(program as WebGLProgram, name);

      const resolution = uniform("u_resolution");
      const time = uniform("u_time");
      const cellSize = uniform("u_cellSize");

      gl.uniform1i(uniform("u_image"), 0);
      gl.uniform1f(
        uniform("u_imageAspect"),
        sourceImage.naturalWidth / sourceImage.naturalHeight,
      );
      gl.uniform1f(uniform("u_showLogo"), 0);
      gl.uniform1i(uniform("u_style"), 1);
      gl.uniform1f(uniform("u_brightness"), 1.08);
      gl.uniform1f(uniform("u_contrast"), 1.35);
      gl.uniform1i(uniform("u_keepColor"), 0);
      gl.uniform3f(uniform("u_darkColor"), 23 / 255, 21 / 255, 18 / 255);
      gl.uniform3f(uniform("u_lightColor"), 238 / 255, 233 / 255, 218 / 255);
      gl.uniform1f(uniform("u_shades"), 2);
      gl.uniform1f(uniform("u_motion"), 1);
      gl.uniform1f(uniform("u_motionAmount"), 0.006);
      gl.uniform1f(uniform("u_motionSpeed"), 0.42);
      gl.uniform1f(uniform("u_shimmer"), 0.065);

      function resize() {
        const bounds = canvas.getBoundingClientRect();
        const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
        const width = Math.max(1, Math.round(bounds.width * pixelRatio));
        const height = Math.max(1, Math.round(bounds.height * pixelRatio));

        if (canvas.width !== width || canvas.height !== height) {
          canvas.width = width;
          canvas.height = height;
        }

        gl.viewport(0, 0, width, height);
        gl.uniform2f(resolution, width, height);
        gl.uniform1f(cellSize, 3 * pixelRatio);
      }

      resizeObserver = new ResizeObserver(resize);
      resizeObserver.observe(canvas);
      resize();

      const startedAt = performance.now();
      let firstFrame = true;

      function render(now: number) {
        frameId = null;

        if (disposed || document.hidden) {
          return;
        }

        resize();
        gl.uniform1f(time, (now - startedAt) / 1000);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

        if (firstFrame) {
          firstFrame = false;
          setReady(true);
        }

        frameId = requestAnimationFrame(render);
      }

      function handleVisibilityChange() {
        if (document.hidden) {
          stopAnimation();
        } else if (frameId === null) {
          frameId = requestAnimationFrame(render);
        }
      }

      document.addEventListener("visibilitychange", handleVisibilityChange);
      frameId = requestAnimationFrame(render);

      return () => {
        document.removeEventListener(
          "visibilitychange",
          handleVisibilityChange,
        );
      };
    }

    let removeVisibilityListener: (() => void) | undefined;
    void setup(gl, canvas)
      .then((cleanup) => {
        removeVisibilityListener = cleanup;
      })
      .catch(() => {
        setReady(false);
      });

    return () => {
      disposed = true;
      abortController.abort();
      stopAnimation();
      removeVisibilityListener?.();
      resizeObserver?.disconnect();
      canvas.removeEventListener("webglcontextlost", handleContextLost);

      if (buffer) {
        gl.deleteBuffer(buffer);
      }
      if (texture) {
        gl.deleteTexture(texture);
      }
      if (program) {
        gl.deleteProgram(program);
      }
    };
  }, []);

  return (
    <div
      className={`relative overflow-hidden bg-[#eee9da] ${className}`}
      aria-hidden="true"
    >
      <Image
        src={FALLBACK_IMAGE}
        alt=""
        fill
        priority
        sizes="(min-width: 1024px) 50vw, 100vw"
        className={`object-cover transition-opacity duration-300 ${
          ready ? "opacity-0" : "opacity-100"
        }`}
      />
      <canvas
        ref={canvasRef}
        className={`absolute inset-0 size-full transition-opacity duration-300 ${
          ready ? "opacity-100" : "opacity-0"
        }`}
      />
    </div>
  );
}
