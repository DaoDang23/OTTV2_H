#include <array>
#include <cstdlib>
#include <iostream>
#include <regex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

struct Piece {
    int id;
    std::string type;
    int row;
    int col;
};

struct Cell {
    int row;
    int col;
    std::string type;
};

struct Move {
    int pieceId;
    int row;
    int col;
};

std::string arraySection(const std::string& state, const std::string& key) {
    const auto keyPosition = state.find("\"" + key + "\"");
    if (keyPosition == std::string::npos) return "[]";
    const auto start = state.find('[', keyPosition);
    const auto end = state.find(']', start);
    if (start == std::string::npos || end == std::string::npos) {
        throw std::runtime_error("Malformed JSON array: " + key);
    }
    return state.substr(start, end - start + 1);
}

std::vector<Piece> parsePieces(const std::string& json) {
    const std::regex expression(
        R"json(\{\s*"id"\s*:\s*(\d+)\s*,\s*"type"\s*:\s*"([A-Z]+)"\s*,\s*"r"\s*:\s*(\d+)\s*,\s*"c"\s*:\s*(\d+)\s*\})json");
    std::vector<Piece> pieces;
    for (std::sregex_iterator it(json.begin(), json.end(), expression), end; it != end; ++it) {
        pieces.push_back({std::stoi((*it)[1]), (*it)[2], std::stoi((*it)[3]), std::stoi((*it)[4])});
    }
    return pieces;
}

std::vector<Cell> parseCells(const std::string& json) {
    const std::regex expression(
        R"json(\{\s*"r"\s*:\s*(\d+)\s*,\s*"c"\s*:\s*(\d+)\s*,\s*"type"\s*:\s*"([A-Z]+)"\s*\})json");
    std::vector<Cell> cells;
    for (std::sregex_iterator it(json.begin(), json.end(), expression), end; it != end; ++it) {
        cells.push_back({std::stoi((*it)[1]), std::stoi((*it)[2]), (*it)[3]});
    }
    return cells;
}

Move decide(const std::string& state) {
    const auto ownPieces = parsePieces(arraySection(state, "yourPieces"));
    const auto enemies = parsePieces(arraySection(state, "visibleEnemies"));
    const auto cells = parseCells(arraySection(state, "visibleCells"));
    if (ownPieces.empty()) throw std::runtime_error("No available pieces");

    std::unordered_map<int, std::string> cellTypes;
    for (const auto& cell : cells) cellTypes[cell.row * 9 + cell.col] = cell.type;

    constexpr std::array<std::array<int, 2>, 4> directions{{
        {{-1, 0}}, {{1, 0}}, {{0, -1}}, {{0, 1}}
    }};
    Move best{};
    int bestDistance = 1000;
    bool found = false;

    for (const auto& piece : ownPieces) {
        for (const auto& direction : directions) {
            const int row = piece.row + direction[0];
            const int col = piece.col + direction[1];
            if (row < 0 || row >= 9 || col < 0 || col >= 9) continue;
            const auto cell = cellTypes.find(row * 9 + col);
            if (cell != cellTypes.end() && cell->second == "OBSTACLE") continue;

            bool occupiedByAlly = false;
            for (const auto& ally : ownPieces) {
                if (ally.id != piece.id && ally.row == row && ally.col == col) occupiedByAlly = true;
            }
            if (occupiedByAlly) continue;

            bool blockedByDraw = false;
            for (const auto& enemy : enemies) {
                if (enemy.row == row && enemy.col == col && enemy.type == piece.type) blockedByDraw = true;
            }
            if (blockedByDraw) continue;

            const int distance = std::abs(4 - row) + std::abs(4 - col);
            if (!found || distance < bestDistance) {
                best = {piece.id, row, col};
                bestDistance = distance;
                found = true;
            }
        }
    }

    if (!found) throw std::runtime_error("No legal move in the observation");
    return best;
}

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        try {
            const auto move = decide(line);
            std::cout << "{\"pieceId\":" << move.pieceId << ",\"to\":["
                      << move.row << ',' << move.col << "]}" << std::endl;
        } catch (const std::exception& error) {
            std::cerr << error.what() << std::endl;
            return 1;
        }
    }
}
