# Bouncy Castle référence des API absentes d'Android (JNDI, etc.) dans du code
# que nous n'utilisons pas : R8 le retire, on fait taire les avertissements.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
