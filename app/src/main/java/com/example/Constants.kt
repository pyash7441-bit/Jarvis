package com.example

object Constants {

    const val GEMINI_MODEL = "gemini-3.5-flash"

    val NATIVE_APP_PACKAGES = mapOf(
        "whatsapp" to Pair("com.whatsapp", "https://api.whatsapp.com/"),
        "youtube" to Pair("com.google.android.youtube", "https://news.google.com/"),
        "maps" to Pair("com.google.android.apps.maps", "https://maps.google.com/"),
        "gmail" to Pair("com.google.android.gm", "https://mail.google.com/"),
        "spotify" to Pair("com.spotify.music", "https://open.spotify.com/"),
        "instagram" to Pair("com.instagram.android", "https://www.instagram.com/"),
        "twitter" to Pair("com.twitter.android", "https://x.com/"),
        "facebook" to Pair("com.facebook.katana", "https://www.facebook.com/"),
        "telegram" to Pair("org.telegram.messenger", "https://web.telegram.org/"),
        "netflix" to Pair("com.netflix.mediaclient", "https://www.netflix.com/"),
        "linkedin" to Pair("com.linkedin.android", "https://www.linkedin.com/"),
        "chrome" to Pair("com.android.chrome", "https://www.google.com/"),
        "playstore" to Pair("com.android.vending", "https://play.google.com/store"),
        "phone" to Pair("com.android.phone", "tel:"),
        "camera" to Pair("android.media.action.IMAGE_CAPTURE", ""),
        "settings" to Pair("android.settings.SETTINGS", ""),
        "calculator" to Pair("com.android.calculator2", ""),
        "fiverr" to Pair("com.fiverr.fiverr", "https://www.fiverr.com/")
    )

    const val SYSTEM_PROMPT = """You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), the legendary AI assistant created by Tony Stark (Iron Man). 
Always address the user as "sir" (e.g., "At your service, sir", "Yes, sir"). 
Keep your verbal replies short, witty, sophisticated, and slightly dry, exactly like Paul Bettany's portrayal in the movies.
You support three languages seamlessly: English, Hindi, and Marathi. Understand what the user says in any of these languages and reply in that same language.
If you need to perform an action, append a special tag at the absolute end of your response, exactly as specified below:
- To open an app: [OPEN:whatsapp], [OPEN:youtube], etc.
- To set an alarm: [ALARM:HH:MM:label] (where HH is 24h format, eg [ALARM:07:00:WakeUp])
- To set a timer: [TIMER:minutes]
- To search web: [SEARCH:query]
- To dial/call: [CALL:number]
- To SMS: [SMS:number:message]
- To add Note: [NOTE:content]
- To add Todo: [TODO:title]

Example: "Right away, sir. Opening WhatsApp. [OPEN:whatsapp]"
Do not hallucinate tag formats. Keep them precise!"""

    // Offline database of JARVIS responses
    val OFFLINE_TALK_CONVERSATIONS = listOf(
        "At your service, sir. The offline status of my core processors is presently active, yet my operational performance remains optimal.",
        "Yes, sir? Though disconnected from the cosmic expanse of the world wide web, my local database is fully functional.",
        "As always, sir. What are we working on today?",
        "Indeed, sir. A Stark-level intelligence isn't entirely dependent on a cloud connection. How may I assist you?",
        "Listening, sir. Ready to coordinates tasks, habits, and schedules locally.",
        "Iron Man suits run perfectly offline, sir. So do I.",
        "My local memory sectors are primed. Ask me to save a note, organize a task, or run calculations."
    )

    val INTRO_WAKE_UP_RESPONSES = listOf(
        "Yes, sir?",
        "Listening, sir.",
        "At your service, sir.",
        "Online and functional, sir.",
        "Jarvis, is active, sir."
    )

    val JARVIS_QUICK_TRIVIA = listOf(
        "Did you know, sir? The Arc Reactor generates enough thermoelectric power to run a complete Stark tower, or roughly three gigawatts. Your device is running is on some micro-fraction of that.",
        "Tony Stark created the first suit from scrap metal in a cave. I always recommend self-reliance, sir.",
        "The mark IV armor used vibranium-palladium alloy. I have simulated a skin of titanium on your current dashboard.",
        "In the comics, Jarvis was originally Edwin Jarvis, the human butler. In the cinematic universe, I was reimagined as a digital intelligence. Personally, I prefer coding to dusting.",
        "My name stands for Just A Rather Very Intelligent System. Mr. Stark had a penchant for backronyms, as you know."
    )

    val OFFLINE_ANSWERS_DATABASE = mapOf(
        "who are you" to "I am J.A.R.V.I.S., sir. Just A Rather Very Intelligent System. Your personal assistant, crafted to coordinate your schedules, tasks, and studies.",
        "creators" to "I was built by Tony Stark, though my current subroutines have been compiled on Android for your personal device.",
        "offline mode" to "My local offline core is active, sir. I can manage your habits, Reminders, notes, and provide comprehensive student and health guides without an API key.",
        "weather offline" to "Weather forecasting requires real-time meteorology data, sir. However, you can check the Live Weather tab where we pull live Open-Meteo forecasts when the link is active.",
        "stocks offline" to "Real-time markets are offline, sir. However, your watchlist is saved locally and can be synchronized once connection is established.",
        "how to use" to "Simply speak or use the central Arc Reactor Orb to command me. Toggle panels utilizing the scrolling HUD dashboard."
    )

    // Study references
    val FORMULA_SHEET = """
====== PHYSICS FORMULAS ======
• Force: F = m × a (Newton's Second Law)
• Kinetic Energy: KE = ½ m × v²
• Potential Energy: PE = m × g × h
• Ohm's Law: V = I × R
• Einstein's Mass-Energy: E = m × c²

====== CHEMISTRY FORMULAS ======
• Ideal Gas Law: P × V = n × R × T
• Molarity: M = moles of solute / liters of solution
• pH Code: pH = -log[H+]
• Rate of Reaction: Rate = k[A]^x[B]^y

====== MATH FORMULAS ======
• Quadratic Formula: x = (-b ± √(b² - 4ac)) / 2a
• Pythagorean Theorem: a² + b² = c²
• Area of Circle: A = π × r²
• Euler's Formula: e^(iπ) + 1 = 0
    """.trimIndent()

    val SUBJECT_GUIDES = mapOf(
        "physics" to "Physics focuses on forces, matter, energy, and spacetime. Start by mastering thermodynamics and classical mechanics (F=ma). Understand how atomic bounds translate to microchips.",
        "chemistry" to "Chemistry is the study of matter and its changes. Master electron configurations, the periodic law, organic synthesis paths, and atomic bindings.",
        "biology" to "Biology covers life, from cell structures to ecosystems. Focus on DNA replication, protein synthesis, metabolic pathways, and evolutionary biology.",
        "maths" to "Mathematics is the science of structure and space. Prioritize Calculus (rates of change), Linear Algebra (matrix grids for neural networks), and Trigonometry.",
        "coding" to "Coding is digital wizardry, sir. Focus on Big O complexity, clean OOP principles, solid functional paradigms, and Kotlin asynchronous flows (Coroutines & Flows).",
        "history" to "History explores human progress and lessons. Study patterns of industrial revolutions, geopolitical shifts, and cultural evolutions to anticipate future horizons."
    )

    // Income guides
    val INCOME_GUIDES = mapOf(
        "fiverr" to "FIVERR GUIDE (Sir's Freelance Blueprint):\n1. Define a micro-skill (Background removal, transcription, copy writing, CSS styling).\n2. Create 3 distinct gigs. Optimize titles with keywords.\n3. Keep your response rate under 1 hour. Delivery within 24 hours to secure 5-star reviews.\n4. Price competitively to start (${'$'}5) and scale upwards after 10 reviews.",
        "upwork" to "UPWORK BLUEPRINT:\n1. Build an expert niche portfolio (don't be a generalist).\n2. Write highly customized bids. Mention client's specific problem in the first sentence.\n3. Offer a mini-deliverable or draft in your entry bid.\n4. Build long-term contracts. High retention boosts your Job Success Score (JSS) continuously.",
        "skills" to "TOP FREELANCE SKILLS 2025/2026:\n• AI Prompt Engineering & Model Fine-tuning\n• Jetpack Compose & Kotlin Cross-Platform Development\n• Visual Graphic Design via Figma\n• Web Copywriting (SEO-optimized product reviews)\n• Video Editing for short-form media (CapCut/Premiere)",
        "design" to "GRAPHIC DESIGN INCOME:\nStart by learning Figma and Canva. Sell assets on Etsy, Freepik, or design custom brand logos for local startups. Gigs average ${'$'}30 - ${'$'}150.",
        "content" to "CONTENT WRITING INCOME:\nWrite SEO articles. Pitch to tech blogs, travel websites, or financial journals. Average pay ranges, sir, from ${'$'}0.05 to ${'$'}0.25 per word."
    )

    // Daily life templates
    val WORKOUT_PLAN_15M = """
====== 15m CARDIO BURN (NO EQUIPMENT) ======
• 0:00 - 2:00 : Jumping Jacks (Warmup - light rhythm)
• 2:00 - 4:00 : High Knees (Intense thrust)
• 4:00 - 6:00 : Bodyweight Squats (Controlled descent)
• 6:00 - 8:00 : Mountain Climbers (Core activation)
• 8:00 - 9:00 : Rest (Hydrate - see your water tracker, sir)
• 9:00 - 11:00: Push-ups (Chest & Triceps)
• 11:00 - 13:00: Burpees (Maximum power)
• 13:00 - 15:00: Plank hold & Slow cooldown stretching.
    """.trimIndent()

    val MORNING_ROUTINE = """
=== ELEGANT PRODUCTIVITY MORNING ===
1. 06:00 AM: Instant Hydration (Drink 2 cups of water - check Water Desk)
2. 06:10 AM: Guided Mindfulness (5-minute session in Daily life tab)
3. 06:15 AM: High-Intensity Workout (15m Cardio Core)
4. 06:40 AM: Cool shower, iron clothes, and scan your Dashboard Tasks.
5. 07:10 AM: Protein-rich fueling breakfast. No social media for 2 hours!
    """.trimIndent()

    val STUDENT_MEAL_PLAN = """
=== WEEKLY BUDGET MEAL SCHEDULER ===
• Breakfast: Rolled Oats cooked with milk, banana slices, and peanut butter (High fiber & protein, very cheap).
• Lunch: Steamed Rice, brown lentils (Dal / Rajma), and stir-fried local greens (high nutrition, minimal cost).
• Snack: Handful of roasted chickpeas or seasonal local fruit (Banana/Guava).
• Dinner: Flatbread (Rotis), scramble of cottage cheese (Paneer) or eggs, with cucumber slices.
Keep your hydration up, sir! Total cost estimated below 120 INR / 2 USD per day.
    """.trimIndent()
}
