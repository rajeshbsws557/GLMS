/**
 * ============================================================================
 * FILE: config.js — API Configuration
 * ============================================================================
 * 
 * PURPOSE:
 * Centralizes the backend API base URL in a single place. Every JavaScript
 * file imports this constant instead of hardcoding the URL. This way, when
 * you deploy to a different server (or switch from local to Azure), you only
 * change ONE line of code.
 * 
 * WHY IS THIS IMPORTANT?
 * Without a centralized config, if the backend URL appears in 20 different
 * fetch() calls across multiple files, you'd need to update all 20 when
 * the server changes. With this config, you update one line. This follows
 * the DRY principle (Don't Repeat Yourself).
 * 
 * INSTRUCTIONS:
 * Replace the URL below with your actual Azure VPS backend URL.
 * Make sure it ends with a trailing slash ("/").
 * 
 * VIVA NOTE:
 * This is a simple but important software engineering practice. In larger
 * applications, you'd use environment variables (e.g., .env files) managed
 * by build tools like Vite or Webpack. For this vanilla JS project, a
 * separate config file achieves the same goal.
 * 
 * ============================================================================
 */

// ============================================================================
// BACKEND API BASE URL
// ============================================================================
// Change this to match your Azure VPS PHP backend address.
// Examples:
//   Local development:  'http://localhost/GLMS/backend/'
//   Azure VPS:          'https://your-azure-vps.com/backend/'
//
// The trailing slash is important! Without it, URLs like:
//   API_BASE_URL + 'auth.php' would become 'http://localhostauth.php'
//   instead of 'http://localhost/auth.php'
// ============================================================================
const API_BASE_URL = 'http://localhost/GLMS/backend/';
