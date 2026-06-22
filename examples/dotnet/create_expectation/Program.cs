// Demonstrates creating a basic MockServer expectation, exercising it with a
// real HTTP request, and verifying that the request was received.
//
// Prerequisites: MockServer running on localhost:1080
//   docker run -d -p 1080:1080 mockserver/mockserver

using MockServer.Client;
using MockServer.Client.Models;

using var client = new MockServerClient("localhost", 1080);

// -------------------------------------------------------------------
// 1. Create an expectation: GET /hello -> 200 "Hello from .NET!"
// -------------------------------------------------------------------
client.When(
    HttpRequest.Request()
        .WithMethod("GET")
        .WithPath("/hello")
).Respond(
    HttpResponse.Response()
        .WithStatusCode(200)
        .WithHeader("Content-Type", "text/plain")
        .WithBody("Hello from .NET!")
);
Console.WriteLine("1. Created expectation: GET /hello -> 200 \"Hello from .NET!\"");

// -------------------------------------------------------------------
// 2. Send a test request through MockServer
// -------------------------------------------------------------------
using var http = new HttpClient();
var response = await http.GetAsync("http://localhost:1080/hello");
var body = await response.Content.ReadAsStringAsync();
Console.WriteLine($"\n--- Test request: GET /hello ---");
Console.WriteLine($"Status: {(int)response.StatusCode}");
Console.WriteLine($"Body:   {body}");

// -------------------------------------------------------------------
// 3. Verify the request was received at least once
// -------------------------------------------------------------------
client.Verify(
    HttpRequest.Request().WithPath("/hello"),
    VerificationTimes.AtLeastTimes(1)
);
Console.WriteLine("\n2. Verified: GET /hello received at least once");

// -------------------------------------------------------------------
// Clean up
// -------------------------------------------------------------------
client.Reset();
Console.WriteLine("\nAll expectations cleared.");
