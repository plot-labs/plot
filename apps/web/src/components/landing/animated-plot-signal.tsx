import Image from "next/image";

const cards = [
  { label: "source", className: "left-[18%] top-[24%] -rotate-6" },
  { label: "signal", className: "right-[16%] top-[22%] rotate-6" },
  { label: "docs", className: "bottom-[22%] left-[20%] rotate-3" },
  { label: "release", className: "bottom-[20%] right-[17%] -rotate-3" },
];

export function AnimatedPlotSignal() {
  return (
    <div
      aria-hidden="true"
      className="plot-signal relative size-full overflow-hidden"
    >
      {[58, 74, 90].map((size, index) => (
        <div
          key={size}
          className="plot-orbit absolute left-1/2 top-1/2 rounded-full border border-foreground/15"
          style={{
            width: `${size}%`,
            height: `${size * 0.58}%`,
            animationDelay: `${index * -1.8}s`,
          }}
        >
          <span className="absolute left-1/2 top-[-4px] size-2 rounded-full bg-foreground/55" />
          <span className="absolute bottom-[-3px] left-[18%] size-1.5 rounded-full bg-foreground/30" />
        </div>
      ))}

      <div className="absolute left-1/2 top-1/2 z-20 grid size-20 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-[24px] border border-foreground/10 bg-background shadow-[0_22px_70px_rgb(18_17_15_/_0.12)]">
        <Image
          src="/plot-icon.svg"
          alt=""
          width={48}
          height={48}
          className="size-12"
        />
      </div>

      {cards.map((card, index) => (
        <div
          key={card.label}
          className={`plot-card absolute z-10 w-[118px] rounded-[14px] border border-white/15 bg-[#151513] px-4 py-3 text-[#faf9f6] shadow-[0_14px_36px_rgb(18_17_15_/_0.16)] ${card.className}`}
          style={{ animationDelay: `${index * -0.6}s` }}
        >
          <div className="font-mono text-[10px] font-semibold uppercase tracking-[0.08em] text-white/75">
            {card.label}
          </div>
          <div className="mt-3 space-y-1.5">
            <div className="h-1.5 w-full rounded-full bg-white/18" />
            <div className="h-1.5 w-3/4 rounded-full bg-white/12" />
            <div className="h-1.5 w-1/2 rounded-full bg-white/10" />
          </div>
        </div>
      ))}

      <style jsx>{`
        .plot-orbit {
          transform: translate(-50%, -50%) rotate(-10deg);
          animation: orbit-drift 8s ease-in-out infinite;
        }

        .plot-card {
          animation: card-float 4s ease-in-out infinite;
        }

        @keyframes orbit-drift {
          50% {
            transform: translate(-50%, -50%) rotate(7deg);
          }
        }

        @keyframes card-float {
          50% {
            translate: 0 -7px;
          }
        }

        @media (prefers-reduced-motion: reduce) {
          .plot-orbit,
          .plot-card {
            animation: none;
          }
        }
      `}</style>
    </div>
  );
}
