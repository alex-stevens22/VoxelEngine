VoxelEngine is a prototype for a multi-threaded, Java-based voxel world engine inspired by Minecraft but architected for modern multi-core performance. Its goal is to build a scalable engine skeleton that can efficiently divide game work—like chunk generation, lighting, and meshing—across multiple threads.

Right now, the project is a simulation framework, not yet a visual 3D renderer, but it lays all the groundwork for one.

PROJECT OVERVIEW
VoxelEngine is a dynamically scaling voxel world simulation that demonstrates a multi-threaded job system, real-time autoscaling of worker threads, and a decoupled render/simulation loop written in Java 21.

In simple terms, it simulates a Minecraft-like engine that runs mock world-generation tasks in parallel to prove out the threading model. The system automatically adjusts how many CPU threads it uses based on system performance and workload (autoscaling). It includes telemetry that tracks how fast the render and simulation loops run, and how backed-up the job queue is.

SYSTEM ARCHITECTURE

Main Game Threads
RenderThread (RT): Simulates the renderer’s workload. Mimics frame rendering by doing artificial “busy work” (~1–2 ms per frame).
SimulationThread (ST): Runs a fixed-timestep world update. Processes game logic, consumes input, and submits jobs for world work.
JobSystem (Workers): Executes background chunk jobs in parallel. Handles generation, lighting, meshing, and simulated GPU uploads.

Dynamic Job System
The JobSystem is the core concurrency engine. It maintains a priority queue of jobs:

P0_CRITICAL – immediate tasks (meshing, lighting near player)

P1_NEAR – nearby chunk generation

P2_BACKGROUND – far-off or low-priority work

It spawns worker threads to process these jobs and includes an autoscaler that measures queue size, render time, and simulation time. The autoscaler automatically adds or removes worker threads based on system load and job backlog.
For low-end CPUs, if no workers are available, it runs a small portion of jobs inline on the simulation thread.

Telemetry System
Tracks performance metrics for:

Render and Simulation frame times

Job queue depth and average wait time

Job execution duration

Worker thread count

These are used by the autoscaler and displayed in the console, for example:
RT 1.52 ms | ST 0.00 ms | Q=0 | wait≈0.00 ms | workers=10

World and Chunk Pipeline
Even though there’s no graphics yet, the world system mimics how Minecraft’s backend would behave.
Each chunk passes through the following stages:
UNLOADED → GENERATING → LIGHTING → MESHING → GPU_UPLOAD_PENDING → READY

Each stage is implemented as a job:

GenJob: Simulates terrain generation

LightJob: Simulates lighting calculations

MeshJob: Simulates mesh construction

Each job uses a small CPU “busy loop” to simulate processing time. When a stage completes, it triggers the next stage automatically via callbacks.

Renderer Stub
The StubRenderer simulates rendering and camera movement. It periodically requests new chunks, simulates GPU uploads, and executes artificial rendering workloads. This keeps the render thread active so that autoscaling and telemetry behave realistically.
