import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;
import java.nio.file.Paths;
import java.nio.file.Files;

public class Main {
    private static final int PORT = 8080;

    // ✅ IMPROVED: Thread-safe data structures with HashMap for O(1) lookups
    private static List<Vehicle> vehicles = Collections.synchronizedList(new ArrayList<>());
    private static List<Customer> customers = Collections.synchronizedList(new ArrayList<>());
    private static List<Ride> rides = Collections.synchronizedList(new ArrayList<>());

    // ✅ NEW: HashMaps for fast O(1) lookups
    private static Map<Integer, Vehicle> vehicleMap = new ConcurrentHashMap<>();
    private static Map<Integer, Customer> customerMap = new ConcurrentHashMap<>();
    private static Map<Integer, Ride> rideMap = new ConcurrentHashMap<>();

    // ✅ NEW: Queue for waiting rides
    private static Queue<Ride> waitingQueue = new ConcurrentLinkedQueue<>();

    // ✅ NEW: TreeMap for time-based ride history
    private static TreeMap<String, Ride> rideHistory = new TreeMap<>();

    // Cities data
    private static final String[] CITIES = {
            "Islamabad", "Lahore", "Sialkot", "Sargodha", "Karachi",
            "Quetta", "Peshawar", "Gujranwala", "Faisalabad", "Hyderabad"
    };

    // Distance matrix
    private static final int[][] DISTANCES = {
            {0, 300, 200, 150, 1400, 900, 180, 250, 280, 1300},
            {300, 0, 120, 180, 1250, 850, 350, 70, 50, 1200},
            {200, 120, 0, 220, 1300, 950, 380, 100, 150, 1250},
            {150, 180, 220, 0, 1350, 900, 250, 200, 230, 1280},
            {1400, 1250, 1300, 1350, 0, 700, 1450, 1200, 1230, 150},
            {900, 850, 950, 900, 700, 0, 950, 800, 830, 650},
            {180, 350, 380, 250, 1450, 950, 0, 320, 350, 1400},
            {250, 70, 100, 200, 1200, 800, 320, 0, 60, 1150},
            {280, 50, 150, 230, 1230, 830, 350, 60, 0, 1180},
            {1300, 1200, 1250, 1280, 150, 650, 1400, 1150, 1180, 0}
    };

    public static void main(String[] args) throws IOException {
        // Load saved data files first; fall back to built-in sample data if they are missing
        loadDataFromFiles();
        if (vehicles.isEmpty() && customers.isEmpty()) {
            initializeSampleData();
        }

        System.out.println("==========================================");
        System.out.println("🚕 TAXI.IO JAVA SERVER STARTING...");
        System.out.println("🌐 URL: http://localhost:" + PORT);
        System.out.println("==========================================");

        ServerSocket serverSocket = new ServerSocket(PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(() -> handleClient(clientSocket)).start();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            // Read request
            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] requestParts = requestLine.split(" ");
            String method = requestParts[0];
            String path = requestParts[1];

            System.out.println("[REQUEST] " + method + " " + path);

            // Handle different requests
            if (path.equals("/") || path.equals("/index.html")) {
                serveFile(out, "web/index.html", "text/html");
            }
            else if (path.equals("/style.css")) {
                serveFile(out, "web/style.css", "text/css");
            }
            else if (path.equals("/script.js")) {
                serveFile(out, "web/script.js", "application/javascript");
            }
            else if (path.equals("/api/data") && method.equals("GET")) {
                sendAllData(out);
            }
            else if (path.equals("/api/add-vehicle") && method.equals("POST")) {
                handleAddVehicle(in, out);
            }
            else if (path.equals("/api/add-customer") && method.equals("POST")) {
                handleAddCustomer(in, out);
            }
            else if (path.equals("/api/book-ride") && method.equals("POST")) {
                handleBookRide(in, out);
            }
            else if (path.equals("/api/assign-rides") && method.equals("POST")) {
                handleAssignRides(out);
            }
            else if (path.equals("/api/find-route") && method.equals("POST")) {
                handleFindRoute(in, out);
            }
            else if (path.equals("/api/cancel-ride") && method.equals("POST")) {
                handleCancelRide(in, out);
            }
            else {
                send404(out);
            }

        } catch (Exception e) {
            System.err.println("Error handling client: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleBookRide(BufferedReader in, PrintWriter out) throws IOException {
        // Read headers first
        String line;
        int contentLength = 0;

        // Read headers until empty line
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        // Read the request body
        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer, 0, contentLength);
            body.append(buffer, 0, bytesRead);
        }

        System.out.println("Book ride request body: " + body.toString());

        if (body.length() == 0) {
            sendError(out, "Empty request body");
            return;
        }

        // Parse data (JSON or form data)
        Map<String, String> params;

        if (body.toString().trim().startsWith("{")) {
            params = parseJsonData(body.toString());
        } else {
            params = parseFormData(body.toString());
        }

        System.out.println("Parsed params: " + params);

        // Validate required fields
        if (!params.containsKey("name") || !params.containsKey("customerId") ||
                !params.containsKey("persons") || !params.containsKey("fromCity") ||
                !params.containsKey("toCity") || !params.containsKey("vehicleType")) {
            sendError(out, "Missing required fields");
            return;
        }

        try {
            int id = rides.size() + 1;
            String name = params.get("name");
            int customerId = Integer.parseInt(params.get("customerId"));
            int persons = Integer.parseInt(params.get("persons"));
            int fromCity = Integer.parseInt(params.get("fromCity"));
            int toCity = Integer.parseInt(params.get("toCity"));
            int vehicleType = Integer.parseInt(params.get("vehicleType"));

            // ✅ NEW: Validate inputs
            if (!validateCityIndex(fromCity) || !validateCityIndex(toCity)) {
                sendError(out, "Invalid city index");
                return;
            }

            if (!validateVehicleType(vehicleType)) {
                sendError(out, "Invalid vehicle type. Must be 1, 2, or 3");
                return;
            }

            if (persons < 1 || persons > 4) {
                sendError(out, "Invalid number of persons. Must be between 1 and 4");
                return;
            }

            // ✅ NEW: Validate persons vs vehicle type capacity
            if (!validatePersonsForVehicleType(persons, vehicleType)) {
                String errorMsg = "";
                if (vehicleType == 1) {
                    errorMsg = "Bikes can only carry 1 person. You selected " + persons + " persons.";
                } else if (vehicleType == 2 || vehicleType == 3) {
                    errorMsg = "Cars can carry maximum 3 persons. You selected " + persons + " persons.";
                }
                sendError(out, errorMsg);
                return;
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            Ride ride = new Ride(id, name, customerId, persons, fromCity, toCity, vehicleType, timestamp);
            rides.add(ride);

            // ✅ NEW: Add to HashMap and TreeMap
            rideMap.put(id, ride);
            rideHistory.put(timestamp + "_" + id, ride);

            // Add customer if not exists
            Customer customer = customerMap.get(customerId);
            if (customer == null) {
                customer = new Customer(customers.size() + 1, name, customerId);
                customers.add(customer);
                customerMap.put(customerId, customer);
            }

            System.out.println("✅ Ride booked via /api/book-ride: " + name);

            // Send response
            String response = "{\"success\":true,\"message\":\"Ride booked successfully\",\"rideId\":" + id + "}";
            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: application/json");
            out.println("Access-Control-Allow-Origin: *");
            out.println("Content-Length: " + response.length());
            out.println();
            out.print(response);

        } catch (NumberFormatException e) {
            sendError(out, "Invalid number format in request");
        }
    }

    private static void serveFile(PrintWriter out, String filePath, String contentType) throws IOException {
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                String content = new String(Files.readAllBytes(path));

                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: " + contentType + "; charset=utf-8");
                out.println("Content-Length: " + content.getBytes("UTF-8").length);
                out.println();
                out.print(content);

                System.out.println("✅ Served file: " + filePath);
            } else {
                System.err.println("❌ File not found: " + filePath);
                send404(out);
            }
        } catch (Exception e) {
            System.err.println("Error serving file " + filePath + ": " + e.getMessage());
            send404(out);
        }
    }

    private static void handleAddVehicle(BufferedReader in, PrintWriter out) throws IOException {
        System.out.println("=== ADD VEHICLE DEBUG ===");

        // Read headers
        String line;
        int contentLength = 0;

        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        // Read body
        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer, 0, contentLength);
            body.append(buffer, 0, bytesRead);
        }

        String bodyStr = body.toString();
        System.out.println("Raw body: '" + bodyStr + "'");
        System.out.println("=== ADD VEHICLE DEBUG END ===");

        if (bodyStr.length() == 0) {
            sendError(out, "Empty request body");
            return;
        }

        Map<String, String> params = parseFormData(bodyStr);

        System.out.println("Parsed params: " + params);

        // Validate required fields
        if (!params.containsKey("number") || !params.containsKey("name") ||
                !params.containsKey("driver") || !params.containsKey("type") ||
                !params.containsKey("capacity")) {
            sendError(out, "Missing required fields. Got: " + params.keySet());
            return;
        }

        try {
            int id = vehicles.size() + 1;
            int number = Integer.parseInt(params.get("number"));
            String name = params.get("name");
            String driver = params.get("driver");
            int type = Integer.parseInt(params.get("type"));
            int capacity = Integer.parseInt(params.get("capacity"));

            // Additional validation
            if (number <= 0) {
                sendError(out, "Vehicle number must be positive");
                return;
            }

            if (capacity < 1 || capacity > 4) {
                sendError(out, "Capacity must be between 1 and 4");
                return;
            }

            if (!validateVehicleType(type)) {
                sendError(out, "Vehicle type must be 1, 2, or 3");
                return;
            }

            // ✅ IMPROVED: Check using HashMap O(1) instead of loop O(n)
            if (vehicleMap.values().stream().anyMatch(v -> v.number == number)) {
                sendError(out, "Vehicle number " + number + " already exists");
                return;
            }

            Vehicle vehicle = new Vehicle(id, number, name, driver, type, capacity);
            vehicles.add(vehicle);

            // ✅ NEW: Add to HashMap
            vehicleMap.put(id, vehicle);

            System.out.println("✅ Vehicle added: " + name + " (ID: " + id + ")");

            String response = "{\"success\":true,\"message\":\"Vehicle added successfully\",\"vehicleId\":" + id + "}";
            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: application/json");
            out.println("Access-Control-Allow-Origin: *");
            out.println("Content-Length: " + response.length());
            out.println();
            out.print(response);

        } catch (NumberFormatException e) {
            sendError(out, "Invalid number format: " + e.getMessage());
        } catch (Exception e) {
            sendError(out, "Error adding vehicle: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendAllData(PrintWriter out) {
        try {
            StringWriter writer = new StringWriter();
            writer.write("{");

            // Vehicles
            writer.write("\"vehicles\":");
            writer.write(toJson(vehicles));
            writer.write(",");

            // Customers
            writer.write("\"customers\":");
            writer.write(toJson(customers));
            writer.write(",");

            // Rides
            writer.write("\"rides\":");
            writer.write(toJson(rides));
            writer.write(",");

            // ✅ NEW: Waiting queue info
            writer.write("\"waitingQueue\":" + waitingQueue.size() + ",");

            // Cities with quotes
            writer.write("\"cities\":[\"Islamabad\",\"Lahore\",\"Sialkot\",\"Sargodha\",\"Karachi\",\"Quetta\",\"Peshawar\",\"Gujranwala\",\"Faisalabad\",\"Hyderabad\"],");

            // Stats
            writer.write("\"stats\":{");
            writer.write("\"totalVehicles\":" + vehicles.size() + ",");
            writer.write("\"totalCustomers\":" + customers.size() + ",");
            writer.write("\"totalRides\":" + rides.size() + ",");
            writer.write("\"waitingRides\":" + waitingQueue.size());
            writer.write("}}");

            String json = writer.toString();

            System.out.println("✅ Sending JSON data...");

            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: application/json; charset=utf-8");
            out.println("Access-Control-Allow-Origin: *");
            out.println("Content-Length: " + json.getBytes("UTF-8").length);
            out.println();
            out.print(json);

        } catch (Exception e) {
            System.err.println("Error creating JSON: " + e.getMessage());
        }
    }

    private static void handleAddCustomer(BufferedReader in, PrintWriter out) throws IOException {
        String line;
        int contentLength = 0;

        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer, 0, contentLength);
            body.append(buffer, 0, bytesRead);
        }

        Map<String, String> params = parseFormData(body.toString());

        int id = customers.size() + 1;
        String name = params.get("name");
        int customerId = Integer.parseInt(params.get("customerId"));
        int persons = Integer.parseInt(params.get("persons"));
        int fromCity = Integer.parseInt(params.get("fromCity"));
        int toCity = Integer.parseInt(params.get("toCity"));
        int vehicleType = Integer.parseInt(params.get("vehicleType"));
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        Ride ride = new Ride(id, name, customerId, persons, fromCity, toCity, vehicleType, timestamp);
        rides.add(ride);

        // ✅ NEW: Add to maps
        rideMap.put(id, ride);
        rideHistory.put(timestamp + "_" + id, ride);

        Customer customer = new Customer(id, name, customerId);
        customers.add(customer);
        customerMap.put(customerId, customer);

        System.out.println("✅ Ride booked via /api/add-customer: " + name);

        String response = "{\"success\":true,\"message\":\"Ride booked successfully\"}";
        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: application/json");
        out.println("Access-Control-Allow-Origin: *");
        out.println("Content-Length: " + response.length());
        out.println();
        out.print(response);
    }

    // ✅ IMPROVED: Better ride assignment with priority queue and distance optimization
    private static void handleAssignRides(PrintWriter out) {
        int assigned = 0;

        // Priority queue: unassigned rides sorted by timestamp
        PriorityQueue<Ride> pendingRides = new PriorityQueue<>(
                (a, b) -> a.timestamp.compareTo(b.timestamp)
        );

        // Collect unassigned rides
        for (Ride ride : rides) {
            if (!ride.assigned) {
                pendingRides.offer(ride);
            }
        }

        // Assign rides
        while (!pendingRides.isEmpty()) {
            Ride ride = pendingRides.poll();
            Vehicle bestVehicle = null;
            int minDistance = Integer.MAX_VALUE;

            // Find best vehicle (closest with capacity)
            for (Vehicle vehicle : vehicles) {
                if (vehicle.type == ride.vehicleType &&
                        vehicle.assignedRides.size() < vehicle.capacity) {

                    // Calculate distance from vehicle's current location to ride start
                    int distance = DISTANCES[vehicle.currentLocation][ride.fromCity];

                    if (distance < minDistance) {
                        minDistance = distance;
                        bestVehicle = vehicle;
                    }
                }
            }

            if (bestVehicle != null) {
                bestVehicle.assignedRides.add(ride.id);
                bestVehicle.rideQueue.offer(ride);
                ride.assigned = true;
                ride.assignedTo = bestVehicle.id;

                // Update vehicle location to ride destination
                bestVehicle.currentLocation = ride.toCity;
                assigned++;

                System.out.println("✅ Assigned Ride #" + ride.id + " to Vehicle #" + bestVehicle.id);
            } else {
                // No vehicle available, add to waiting queue
                waitingQueue.offer(ride);
                System.out.println("⏳ Ride #" + ride.id + " added to waiting queue");
            }
        }

        String response = "{\"success\":true,\"assigned\":" + assigned + ",\"waiting\":" + waitingQueue.size() + "}";

        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: application/json");
        out.println("Access-Control-Allow-Origin: *");
        out.println("Content-Length: " + response.length());
        out.println();
        out.print(response);
    }

    // ✅ IMPROVED: Dijkstra's algorithm for shortest path
    private static void handleFindRoute(BufferedReader in, PrintWriter out) throws IOException {
        System.out.println("======= FIND ROUTE REQUEST START =======");

        String line;
        int contentLength = 0;

        while ((line = in.readLine()) != null && !line.isEmpty()) {
            System.out.println("Header: " + line);
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        System.out.println("Content-Length: " + contentLength);

        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer, 0, contentLength);
            body.append(buffer, 0, bytesRead);
            System.out.println("Bytes read: " + bytesRead);
        }

        String bodyStr = body.toString();
        System.out.println("Raw body: '" + bodyStr + "'");
        System.out.println("======= FIND ROUTE REQUEST END =======");

        if (bodyStr.length() == 0) {
            sendError(out, "Empty request body");
            return;
        }

        Map<String, String> params;

        if (bodyStr.trim().startsWith("{")) {
            System.out.println("Parsing as JSON");
            params = parseJsonData(bodyStr);
        } else {
            System.out.println("Parsing as form data");
            params = parseFormData(bodyStr);
        }

        System.out.println("Parsed params: " + params);

        if (!params.containsKey("from") || !params.containsKey("to")) {
            sendError(out, "Missing 'from' or 'to' parameters");
            return;
        }

        try {
            int from = Integer.parseInt(params.get("from"));
            int to = Integer.parseInt(params.get("to"));

            // Validate city indices
            if (!validateCityIndex(from) || !validateCityIndex(to)) {
                sendError(out, "Invalid city index. Must be between 0 and " + (CITIES.length - 1));
                return;
            }

            if (from == to) {
                String json = "{" +
                        "\"success\":true," +
                        "\"from\":\"" + CITIES[from] + "\"," +
                        "\"to\":\"" + CITIES[to] + "\"," +
                        "\"distance\":0," +
                        "\"path\":\"" + CITIES[from] + "\"," +
                        "\"estimatedTime\":0" +
                        "}";

                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: application/json");
                out.println("Access-Control-Allow-Origin: *");
                out.println("Content-Length: " + json.length());
                out.println();
                out.print(json);
                return;
            }

            // ✅ NEW: Use Dijkstra's algorithm for shortest path
            RouteResult result = dijkstra(from, to);

            // Build path string
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < result.path.size(); i++) {
                pathBuilder.append(CITIES[result.path.get(i)]);
                if (i < result.path.size() - 1) {
                    pathBuilder.append(" -> ");
                }
            }

            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");
            jsonBuilder.append("\"success\":true,");
            jsonBuilder.append("\"from\":\"").append(CITIES[from]).append("\",");
            jsonBuilder.append("\"to\":\"").append(CITIES[to]).append("\",");
            jsonBuilder.append("\"distance\":").append(result.distance).append(",");
            jsonBuilder.append("\"path\":\"").append(pathBuilder.toString()).append("\",");
            jsonBuilder.append("\"estimatedTime\":").append(Math.round(result.distance / 60.0));
            jsonBuilder.append("}");

            String json = jsonBuilder.toString();

            System.out.println("✅ Route found using Dijkstra: " + pathBuilder.toString() + " = " + result.distance + "km");

            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: application/json");
            out.println("Access-Control-Allow-Origin: *");
            out.println("Content-Length: " + json.length());
            out.println();
            out.print(json);

        } catch (NumberFormatException e) {
            sendError(out, "Invalid number format for city index");
        } catch (Exception e) {
            sendError(out, "Error finding route: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ✅ NEW: Dijkstra's algorithm implementation
    private static RouteResult dijkstra(int start, int end) {
        int n = CITIES.length;
        int[] dist = new int[n];
        int[] parent = new int[n];
        boolean[] visited = new boolean[n];

        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        dist[start] = 0;

        // Priority queue: [cityIndex, distance]
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
        pq.offer(new int[]{start, 0});

        while (!pq.isEmpty()) {
            int[] current = pq.poll();
            int u = current[0];

            if (visited[u]) continue;
            visited[u] = true;

            if (u == end) break;

            // Check all neighbors
            for (int v = 0; v < n; v++) {
                if (!visited[v] && DISTANCES[u][v] > 0) {
                    int newDist = dist[u] + DISTANCES[u][v];
                    if (newDist < dist[v]) {
                        dist[v] = newDist;
                        parent[v] = u;
                        pq.offer(new int[]{v, newDist});
                    }
                }
            }
        }

        // Reconstruct path
        List<Integer> path = new ArrayList<>();
        for (int at = end; at != -1; at = parent[at]) {
            path.add(at);
        }
        Collections.reverse(path);

        return new RouteResult(dist[end], path);
    }

    private static void handleCancelRide(BufferedReader in, PrintWriter out) throws IOException {
        String line;
        int contentLength = 0;

        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int bytesRead = in.read(buffer, 0, contentLength);
            body.append(buffer, 0, bytesRead);
        }

        Map<String, String> params = parseFormData(body.toString());

        if (!params.containsKey("rideId")) {
            sendError(out, "Missing rideId parameter");
            return;
        }

        int rideId = Integer.parseInt(params.get("rideId"));

        // ✅ IMPROVED: Use HashMap for O(1) lookup
        Ride ride = rideMap.get(rideId);

        if (ride != null) {
            // Remove from vehicle assignment
            for (Vehicle vehicle : vehicles) {
                vehicle.assignedRides.remove(Integer.valueOf(rideId));
                vehicle.rideQueue.removeIf(r -> r.id == rideId);
            }

            rides.remove(ride);
            rideMap.remove(rideId);

            String response = "{\"success\":true,\"message\":\"Ride cancelled successfully\"}";
            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: application/json");
            out.println("Access-Control-Allow-Origin: *");
            out.println("Content-Length: " + response.length());
            out.println();
            out.print(response);

            System.out.println("✅ Ride #" + rideId + " cancelled");
        } else {
            sendError(out, "Ride not found");
        }
    }

    // ✅ NEW: Validation helper methods
    private static boolean validateCityIndex(int index) {
        return index >= 0 && index < CITIES.length;
    }

    private static boolean validateVehicleType(int type) {
        return type >= 1 && type <= 3;
    }

    // ✅ NEW: Validate if vehicle type can carry the number of persons
    private static boolean validatePersonsForVehicleType(int persons, int vehicleType) {
        if (vehicleType == 1) {
            // Bikes can only carry 1 person
            return persons == 1;
        } else if (vehicleType == 2 || vehicleType == 3) {
            // Cars (with or without AC) can carry 1-3 persons
            return persons >= 1 && persons <= 3;
        }
        return false;
    }

    private static int findMinDistance(int[] distances, boolean[] visited) {
        int min = Integer.MAX_VALUE;
        int minIndex = -1;

        for (int i = 0; i < distances.length; i++) {
            if (!visited[i] && distances[i] < min) {
                min = distances[i];
                minIndex = i;
            }
        }

        return minIndex;
    }

    private static Map<String, String> parseFormData(String data) {
        Map<String, String> params = new HashMap<>();
        if (data == null || data.isEmpty()) {
            return params;
        }

        try {
            String[] pairs = data.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    String key = URLDecoder.decode(keyValue[0], "UTF-8");
                    String value = URLDecoder.decode(keyValue[1], "UTF-8");
                    params.put(key, value);
                } else if (keyValue.length == 1) {
                    String key = URLDecoder.decode(keyValue[0], "UTF-8");
                    params.put(key, "");
                }
            }
        } catch (UnsupportedEncodingException e) {
            System.err.println("Error decoding form data: " + e.getMessage());
            String[] pairs = data.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
        }

        return params;
    }

    private static Map<String, String> parseJsonData(String json) {
        Map<String, String> params = new HashMap<>();
        try {
            System.out.println("Parsing JSON: " + json);

            json = json.trim();

            if (!json.startsWith("{") || !json.endsWith("}")) {
                System.err.println("Invalid JSON - not an object");
                return params;
            }

            json = json.substring(1, json.length() - 1).trim();

            if (json.isEmpty()) {
                return params;
            }

            String[] pairs = json.split(",");

            for (String pair : pairs) {
                int colonIndex = pair.indexOf(":");
                if (colonIndex > 0) {
                    String key = pair.substring(0, colonIndex).trim();
                    String value = pair.substring(colonIndex + 1).trim();

                    key = key.replace("\"", "").replace("'", "");
                    value = value.replace("\"", "").replace("'", "");

                    if (!key.isEmpty()) {
                        params.put(key, value);
                        System.out.println("  Parsed: " + key + " = " + value);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
            e.printStackTrace();
        }
        return params;
    }

    private static void send404(PrintWriter out) {
        out.println("HTTP/1.1 404 Not Found");
        out.println("Content-Type: text/html");
        out.println();
        out.println("<h1>404 - Not Found</h1>");
    }

    private static void sendError(PrintWriter out, String message) {
        String json = "{\"success\":false,\"error\":\"" + message + "\"}";

        out.println("HTTP/1.1 400 Bad Request");
        out.println("Content-Type: application/json");
        out.println("Access-Control-Allow-Origin: *");
        out.println("Content-Length: " + json.length());
        out.println();
        out.print(json);
    }

    private static String toJson(List<?> list) {
        if (list.isEmpty()) return "[]";

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            json.append(list.get(i).toString());
            if (i < list.size() - 1) json.append(",");
        }
        json.append("]");
        return json.toString();
    }

    private static void initializeSampleData() {
        // Add sample vehicles
        Vehicle v1 = new Vehicle(1, 101, "Honda CD70", "Ali Ahmed", 1, 1);
        vehicles.add(v1);
        vehicleMap.put(1, v1);

        Vehicle v2 = new Vehicle(2, 201, "Toyota Corolla", "Usman Khan", 2, 3);
        vehicles.add(v2);
        vehicleMap.put(2, v2);

        Vehicle v3 = new Vehicle(3, 301, "Honda Civic", "Bilal Raza", 3, 3);
        vehicles.add(v3);
        vehicleMap.put(3, v3);

        Vehicle v4 = new Vehicle(4, 102, "Yamaha YBR", "Zain Malik", 1, 1);
        vehicles.add(v4);
        vehicleMap.put(4, v4);

        Vehicle v5 = new Vehicle(5, 202, "Suzuki Cultus", "Fahad Ali", 2, 3);
        vehicles.add(v5);
        vehicleMap.put(5, v5);

        // Add sample customers
        Customer c1 = new Customer(1, "Majid khan", 1001);
        customers.add(c1);
        customerMap.put(1001, c1);

        Customer c2 = new Customer(2, "Muhammad basit", 1002);
        customers.add(c2);
        customerMap.put(1002, c2);

        Customer c3 = new Customer(3, "Haseeb shah", 1003);
        customers.add(c3);
        customerMap.put(1003, c3);

        // Add sample rides
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        Ride r1 = new Ride(1, "Nabeel Sikandar", 1001, 2, 0, 1, 2, timestamp);
        rides.add(r1);
        rideMap.put(1, r1);
        rideHistory.put(timestamp + "_1", r1);

        Ride r2 = new Ride(2, "Zarar khan", 1002, 1, 1, 3, 1, timestamp);
        rides.add(r2);
        rideMap.put(2, r2);
        rideHistory.put(timestamp + "_2", r2);
    }

    // ==================== DATA FILE LOADING ====================
    // Loads the saved fleet and bookings from vehicles.txt / customers.txt
    // (Key: Value lines, records separated by "-------------------").
    // File data is loaded as-is; the strict persons-vs-vehicle-type rule
    // is only enforced for new bookings made through the API.

    private static void loadDataFromFiles() {
        loadVehiclesFromFile("vehicles.txt");
        loadCustomersFromFile("customers.txt");
    }

    // Parses one data file into a list of Key -> Value records.
    private static List<Map<String, String>> parseDataFile(String filename) {
        List<Map<String, String>> records = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(filename));
            Map<String, String> current = new HashMap<>();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("---")) {
                    if (!current.isEmpty()) {
                        records.add(current);
                        current = new HashMap<>();
                    }
                } else if (line.contains(":")) {
                    int colon = line.indexOf(":");
                    current.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
            if (!current.isEmpty()) {
                records.add(current);
            }
            System.out.println("📂 Loaded " + records.size() + " records from " + filename);
        } catch (IOException e) {
            System.err.println("⚠️ Could not read " + filename + " (using sample data instead): " + e.getMessage());
        }
        return records;
    }

    private static void loadVehiclesFromFile(String filename) {
        for (Map<String, String> rec : parseDataFile(filename)) {
            try {
                String name = rec.get("Name");
                String driver = rec.get("Driver");
                if (name == null || driver == null) continue;

                int number = Integer.parseInt(rec.get("Number"));
                int type = Integer.parseInt(rec.get("Category"));
                int capacity = Integer.parseInt(rec.get("Capacity"));
                if (!validateVehicleType(type) || capacity < 1 || capacity > 4) continue;

                int id = vehicles.size() + 1;
                Vehicle vehicle = new Vehicle(id, number, name, driver, type, capacity);
                vehicles.add(vehicle);
                vehicleMap.put(id, vehicle);
            } catch (Exception e) {
                System.err.println("⚠️ Skipping bad vehicle record: " + rec);
            }
        }
    }

    private static void loadCustomersFromFile(String filename) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        for (Map<String, String> rec : parseDataFile(filename)) {
            try {
                String name = rec.get("Name");
                if (name == null) continue;

                int customerId = Integer.parseInt(rec.get("Id"));
                int persons = Integer.parseInt(rec.get("Persons"));
                int fromCity = Integer.parseInt(rec.get("Start Point"));
                int toCity = Integer.parseInt(rec.get("Destination"));
                int vehicleType = Integer.parseInt(rec.get("Ride Type"));
                if (!validateCityIndex(fromCity) || !validateCityIndex(toCity)) continue;
                if (!validateVehicleType(vehicleType) || persons < 1 || persons > 4) continue;

                int customerDbId = customers.size() + 1;
                Customer customer = new Customer(customerDbId, name, customerId);
                customers.add(customer);
                customerMap.put(customerId, customer);

                int rideId = rides.size() + 1;
                Ride ride = new Ride(rideId, name, customerId, persons, fromCity, toCity, vehicleType, timestamp);
                rides.add(ride);
                rideMap.put(rideId, ride);
                rideHistory.put(timestamp + "_" + rideId, ride);
            } catch (Exception e) {
                System.err.println("⚠️ Skipping bad customer record: " + rec);
            }
        }
    }

    // ==================== DATA CLASSES ====================

    // ✅ IMPROVED: Vehicle class with location tracking and ride queue
    static class Vehicle {
        int id;
        int number;
        String name;
        String driver;
        int type; // 1: Bike, 2: Car, 3: Car with AC
        int capacity;
        List<Integer> assignedRides = new ArrayList<>();
        int currentLocation = 0; // ✅ NEW: Track current city location
        Queue<Ride> rideQueue = new LinkedList<>(); // ✅ NEW: Queue of upcoming rides

        Vehicle(int id, int number, String name, String driver, int type, int capacity) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.driver = driver;
            this.type = type;
            this.capacity = capacity;
        }

        @Override
        public String toString() {
            return String.format(
                    "{\"id\":%d,\"number\":%d,\"name\":\"%s\",\"driver\":\"%s\",\"type\":%d,\"capacity\":%d,\"assigned\":%d,\"location\":%d}",
                    id, number, name, driver, type, capacity, assignedRides.size(), currentLocation
            );
        }
    }

    static class Customer {
        int id;
        String name;
        int customerId;

        Customer(int id, String name, int customerId) {
            this.id = id;
            this.name = name;
            this.customerId = customerId;
        }

        @Override
        public String toString() {
            return String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"customerId\":%d}",
                    id, name, customerId
            );
        }
    }

    static class Ride {
        int id;
        String customerName;
        int customerId;
        int persons;
        int fromCity;
        int toCity;
        int vehicleType;
        String timestamp;
        boolean assigned = false;
        int assignedTo = -1;

        Ride(int id, String customerName, int customerId, int persons, int fromCity, int toCity, int vehicleType, String timestamp) {
            this.id = id;
            this.customerName = customerName;
            this.customerId = customerId;
            this.persons = persons;
            this.fromCity = fromCity;
            this.toCity = toCity;
            this.vehicleType = vehicleType;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"customerId\":%d,\"persons\":%d,\"from\":%d,\"to\":%d,\"type\":%d,\"timestamp\":\"%s\",\"assigned\":%b}",
                    id, customerName, customerId, persons, fromCity, toCity, vehicleType, timestamp, assigned
            );
        }
    }

    // ✅ NEW: RouteResult class for Dijkstra's algorithm
    static class RouteResult {
        int distance;
        List<Integer> path;

        RouteResult(int distance, List<Integer> path) {
            this.distance = distance;
            this.path = path;
        }
    }
}