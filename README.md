# Taxi.io 🚕

A ride-booking web server written in pure Java — no frameworks. It serves a web interface and a JSON API for booking rides between Pakistani cities, dispatching the nearest available vehicle, and finding the shortest route with **Dijkstra's algorithm**.

## Features

- **Book rides** — pick a customer, passenger count, origin, destination and vehicle type
- **Shortest route** — Dijkstra's algorithm over a 10-city distance graph, with path and ETA
- **Smart dispatch** — a priority queue assigns each ride to the nearest available vehicle with enough capacity; unassigned rides wait in a queue
- **Fleet management** — add vehicles (Bike / Car / Car with AC) with drivers and capacities
- **Ride history** — time-ordered with a `TreeMap`, O(1) lookups with `ConcurrentHashMap`
- **Validation** — city indices, vehicle types and passenger limits (bikes: 1 person, cars: up to 3)
- **Web UI** — served from `web/` at `http://localhost:8080`
- **JSON API** — every action is also available as a REST-style endpoint

## Data structures used

| Structure | Where it is used |
|---|---|
| Graph + Dijkstra's algorithm | Shortest route between cities |
| `PriorityQueue` | Ride dispatch (earliest first) and Dijkstra's frontier |
| `ConcurrentHashMap` | O(1) vehicle / customer / ride lookups |
| `ConcurrentLinkedQueue` | Waiting queue for unassigned rides |
| `TreeMap` | Time-ordered ride history |
| Synchronized `ArrayList` | Thread-safe in-memory storage |

## Cities

Islamabad, Lahore, Sialkot, Sargodha, Karachi, Quetta, Peshawar, Gujranwala, Faisalabad, Hyderabad.

## Vehicle types

| Type | Vehicle | Capacity |
|---|---|---|
| 1 | Bike | 1 person |
| 2 | Car | Up to 3 people |
| 3 | Car with AC | Up to 3 people |

## API

| Method | Endpoint | Description |
|---|---|---|
| GET | `/` | Web interface |
| GET | `/api/data` | All vehicles, customers, rides and stats |
| POST | `/api/add-vehicle` | Add a vehicle |
| POST | `/api/add-customer` | Add a customer / book a ride |
| POST | `/api/book-ride` | Book a ride |
| POST | `/api/assign-rides` | Dispatch waiting rides to vehicles |
| POST | `/api/find-route` | Shortest route between two cities |
| POST | `/api/cancel-ride` | Cancel a ride |

## How to run

**Windows (easiest):** double-click `run.bat`. It compiles `src/Main.java` with IntelliJ's bundled Java and starts the server.

**Any system with Java 17+:**

```bash
javac -encoding UTF-8 src/Main.java -d out
java -cp out Main
```

Then open **http://localhost:8080** in your browser.

## Project structure

```
taxi-io/
├── src/
│   └── Main.java          # The whole server: HTTP handling, API, Dijkstra, dispatch
├── web/                  # Web interface (coming soon)
│   ├── index.html
│   ├── style.css
│   └── script.js
├── customers.txt          # Booking records — loaded at startup (built-in sample data is used if the file is missing)
├── vehicles.txt           # Fleet records — loaded at startup
├── run.bat                # Windows one-click build & run
└── dsa_project.iml        # IntelliJ IDEA module file
```

## Tech

Java 17 · No external libraries — the HTTP server is built on `java.net.ServerSocket`, and every request is handled on its own thread.
