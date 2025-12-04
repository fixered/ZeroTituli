// use an integer for version numbers
version = 7


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

     description = "Live streams from Hattrick."
    authors = listOf("fixered")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://logowiki.net/wp-content/uploads/imgp/Hattrick-Logo-1-5512.jpg"
}
