package ca.wonderlan.callisto.data

data class Article(
    val number: Long,
    val messageId: String,
    val subject: String,
    val from: String,
    val date: String,
    val body: String
)
